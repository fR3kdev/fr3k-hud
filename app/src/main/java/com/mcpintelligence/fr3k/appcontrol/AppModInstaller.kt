package com.mcpintelligence.fr3k.appcontrol

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import com.mcpintelligence.fr3k.capability.appmod.*
import org.json.JSONObject
import java.io.File

object AppModInstaller {
    fun install(context: Context, workflow: AppModWorkflow, jobId: String, original: Boolean = false): Int {
        val store = workflow.store; val job = requireNotNull(store.getJob(jobId))
        val files = if (original) requireNotNull(OriginalArchive(context).lookup(requireNotNull(job.artifact).identity)).files else workflow.outputs(jobId)
        if (job.stage == ModificationStage.INSTALLING) {
            val saved = requireNotNull(store.record("install_session", jobId))
            require(!saved.optBoolean("terminal") && saved.getBoolean("original") == original)
            val sessionId = saved.getInt("sessionId")
            require(context.packageManager.packageInstaller.mySessions.any { it.sessionId == sessionId }) { "Platform session ended; reopen the job to reconcile its result" }
            // A force-stop can revoke the original PendingIntent while the platform session
            // survives. Recommit the sealed, permission-pending session with a fresh callback.
            store.transaction {
                val current = requireNotNull(store.record("install_session", jobId))
                require(current.getInt("sessionId") == sessionId && !current.optBoolean("terminal"))
                current.put("confirmationOpened", false).put("resumedAt", System.currentTimeMillis())
                current.remove("lastStatus")
                store.putRecord("install_session", jobId, current)
            }
            context.packageManager.packageInstaller.openSession(sessionId).use { it.commit(callback(context, jobId, sessionId).intentSender) }
            android.util.Log.i("AppModInstaller", "resumed job=$jobId session=$sessionId freshCallback=true")
            return sessionId
        }
        require(job.stage in setOf(ModificationStage.PREPARED, ModificationStage.INSTALLED, ModificationStage.COMPLETED, ModificationStage.FAILED, ModificationStage.INTERRUPTED))
        require(context.packageManager.canRequestPackageInstalls()) { "Allow installation from HUD, then review installation again." }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val preview = requireNotNull(store.record("preview", jobId))
        val expectedPackage = if (original) job.artifact!!.packageName else preview.getString("clonePackage").ifEmpty { job.artifact!!.packageName }
        params.setAppPackageName(expectedPackage); params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                files.forEachIndexed { i, file -> file.inputStream().use { input -> session.openWrite("part-$i.apk", 0, file.length()).use { output -> input.copyTo(output); session.fsync(output) } } }
                store.transaction {
                    require(store.getJob(jobId)?.stage != ModificationStage.INSTALLING) { "Installation already in progress" }
                    store.putRecord("install_session", jobId, JSONObject().put("sessionId", sessionId).put("original", original).put("package", expectedPackage).put("terminal", false).put("createdAt", System.currentTimeMillis()))
                    store.saveJob(job.copy(stage = ModificationStage.INSTALLING, updatedAtMillis = System.currentTimeMillis(), installerStatus = InstallationStatus.INSTALLER_OPENED, error = null))
                }
                android.util.Log.i("AppModInstaller", "created job=$jobId session=$sessionId")
                session.commit(callback(context, jobId, sessionId).intentSender)
            }
        } catch (failure: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            store.transaction {
                if (store.record("install_session", jobId)?.optInt("sessionId") == sessionId) {
                    store.putRecord("install_session", jobId, store.record("install_session", jobId)!!.put("terminal", true))
                    store.saveJob(job.copy(stage = ModificationStage.PREPARED, updatedAtMillis = System.currentTimeMillis(), installerStatus = InstallationStatus.FAILED, error = failure.message?.take(1000)))
                }
            }
            throw failure
        }
        return sessionId
    }
    private fun callback(context: Context, jobId: String, sessionId: Int): PendingIntent = PendingIntent.getBroadcast(context, sessionId,
        Intent(context, AppModInstallReceiver::class.java).putExtra("jobId", jobId).putExtra("sessionId", sessionId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    /** Missing session + unmatched installed bytes is an unknown platform outcome, never inferred cancellation. */
    fun reconcile(context: Context, workflow: AppModWorkflow) {
        val store = workflow.store
        for (snapshot in store.listJobs().filter { it.stage == ModificationStage.INSTALLING }) store.transaction {
            val job = store.getJob(snapshot.jobId) ?: return@transaction
            val session = store.record("install_session", job.jobId) ?: return@transaction
            if (job.stage != ModificationStage.INSTALLING || session.optBoolean("terminal")) return@transaction
            if (context.packageManager.packageInstaller.getSessionInfo(session.getInt("sessionId")) != null) return@transaction
            if (!verifyInstalled(context, workflow, job.jobId)) {
                store.saveJob(job.copy(stage = ModificationStage.PREPARED, installerStatus = InstallationStatus.FAILED,
                    updatedAtMillis = System.currentTimeMillis(), error = "Platform session ended without a durable result; prepared copy retained. Review before retrying."))
            }
            store.putRecord("install_session", job.jobId, session.put("terminal", true).put("reconciledAt", System.currentTimeMillis()))
        }
    }
    fun verifyInstalled(context: Context, workflow: AppModWorkflow, jobId: String): Boolean = workflow.store.transaction {
        val record = requireNotNull(workflow.store.record("install_session", jobId))
        val observed = runCatching { AndroidIdentityReader.of(context, record.getString("package")) }.getOrNull() ?: return@transaction false
        val job = requireNotNull(workflow.store.getJob(jobId))
        val expected = if (record.getBoolean("original")) job.artifact!!.identity else {
            val preview = requireNotNull(workflow.store.record("preview", jobId)); val source = job.artifact!!
            com.mcpintelligence.fr3k.capability.AppIdentity(preview.getString("clonePackage").ifEmpty { source.packageName }, source.versionCode,
                LspatchPatchEngine.signerSha256(), workflow.outputs(jobId).map(ApkHashing::sha256))
        }
        val matches = observed == expected
        if (matches) workflow.store.saveJob(job.copy(stage = if (record.getBoolean("original")) ModificationStage.COMPLETED else ModificationStage.INSTALLED,
            updatedAtMillis = System.currentTimeMillis(), installerStatus = if (record.getBoolean("original")) InstallationStatus.ORIGINAL_RESTORED else InstallationStatus.INSTALLED, error = null))
        matches
    }
}

class AppModInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val app = context.applicationContext as com.mcpintelligence.fr3k.Fr3kApplication
                val store = app.modificationStore
                val id = intent.getStringExtra("jobId") ?: return@Thread
                val sessionId = intent.getIntExtra("sessionId", -1)
                store.transaction {
                    val session = store.record("install_session", id) ?: return@transaction
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    android.util.Log.i("AppModInstaller", "callback job=$id session=$sessionId status=$status platformSession=${intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)} message=${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                    if (session.getInt("sessionId") != sessionId || session.optBoolean("terminal")) return@transaction
                    val job = store.getJob(id) ?: return@transaction
                    session.put("lastStatus", status).put("callbackAt", System.currentTimeMillis()).put("message", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                    try {
                        when (status) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                if (!session.optBoolean("confirmationOpened")) {
                                    val confirmation = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: error("Installer confirmation missing")
                                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(confirmation)
                                    session.put("confirmationOpened", true)
                                }
                            }
                            PackageInstaller.STATUS_SUCCESS -> {
                                require(AppModInstaller.verifyInstalled(context, app.appModWorkflow, id)) { "Installed artifact differs from prepared output" }
                                session.put("terminal", true)
                            }
                            else -> {
                                store.saveJob(job.copy(stage = ModificationStage.PREPARED, updatedAtMillis = System.currentTimeMillis(),
                                    installerStatus = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) InstallationStatus.CANCELLED else InstallationStatus.FAILED,
                                    error = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(1000) ?: "Installation failed"))
                                // ABORTED is a platform report. Only a measured button action proves user cancellation.
                                session.put("terminal", true)
                            }
                        }
                    } catch (failure: Exception) {
                        store.saveJob(job.copy(stage = ModificationStage.PREPARED, installerStatus = InstallationStatus.FAILED,
                            updatedAtMillis = System.currentTimeMillis(), error = failure.message?.take(1000)))
                        session.put("terminal", true)
                    }
                    store.putRecord("install_session", id, session)
                }
            } finally { pending.finish() }
        }.start()
    }
}
