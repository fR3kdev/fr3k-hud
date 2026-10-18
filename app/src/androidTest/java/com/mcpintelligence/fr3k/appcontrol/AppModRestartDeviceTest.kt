package com.mcpintelligence.fr3k.appcontrol

import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.capability.appmod.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.json.*
import org.junit.Assert.*
import org.junit.Test

/** These two methods are invoked separately with an actual host force-stop between them. */
class AppModRestartDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Fr3kApplication
    @Test fun createRestartCheckpoint() = runBlocking {
        val w = app.appModWorkflow
        val source = w.artifacts.capture(listOf({ app.assets.open("app-control/appmod-gallery-fixture.apk") }), SourceKind.IMPORTED_APK, "Owned restart fixture")
        val report = w.analyze(source.id)
        val target = w.suggestions(report.analysisId).single { it.title.contains("gallery_caption") }
        val preview = w.preview(report.analysisId, JSONArray().put(JSONObject().put("suggestionId", target.suggestionId).put("replacement", "Restart notes")))
        val id = preview.getString("previewId")
        val job = w.prepare(id, w.approveFromUi(id, preview.getString("digest")))
        // Retain a separate in-flight checkpoint to verify startup classification, not a false transfer PASS.
        val interruptedId = java.util.UUID.randomUUID().toString()
        w.store.saveJob(job.copy(jobId = interruptedId, stage = ModificationStage.PREPARING, outputApkPath = null, outputSha256 = null,
            updatedAtMillis = System.currentTimeMillis() - 1000))
        w.store.putRecord("test_restart", "owned", JSONObject().put("preparedId", id).put("interruptedId", interruptedId)
            .put("outputHash", job.outputSha256).put("sourceId", source.id))
    }
    @Test fun verifyRestartCheckpoint() = runBlocking {
        val w = app.appModWorkflow
        val record = requireNotNull(w.store.record("test_restart", "owned"))
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (w.store.getJob(record.getString("interruptedId"))?.stage != ModificationStage.INTERRUPTED && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertEquals(ModificationStage.INTERRUPTED, w.store.getJob(record.getString("interruptedId"))!!.stage)
        val preparedId = record.getString("preparedId")
        assertEquals(ModificationStage.PREPARED, w.store.getJob(preparedId)!!.stage)
        assertEquals(record.getString("outputHash"), ApkHashing.sha256(w.outputs(preparedId).first()))
        assertNotNull(OriginalArchive(app).lookup(w.artifacts.get(record.getString("sourceId")).artifact.identity))
        val preview = w.store.record("preview", preparedId)!!
        val result = w.prepare(preparedId, w.approveFromUi(preparedId, preview.getString("digest")))
        assertEquals(record.getString("outputHash"), result.outputSha256)
    }
    @Test fun createPlatformSessionCheckpoint() = runBlocking {
        createRestartCheckpoint()
        val w = app.appModWorkflow
        val record = w.store.record("test_restart", "owned")!!
        val id = record.getString("preparedId")
        val sessionId = AppModInstaller.install(app, w, id)
        val deadline = android.os.SystemClock.elapsedRealtime() + 30000
        while (w.store.record("install_session", id)?.optBoolean("confirmationOpened") != true && android.os.SystemClock.elapsedRealtime() < deadline) kotlinx.coroutines.delay(100)
        assertTrue(w.store.record("install_session", id)!!.getBoolean("confirmationOpened"))
        assertNotNull(app.packageManager.packageInstaller.getSessionInfo(sessionId))
        w.store.putRecord("test_restart", "platform", record.put("sessionId", sessionId))
    }

    @Test fun verifyPlatformSessionAfterProcessDeath() = runBlocking {
        val w = app.appModWorkflow
        val record = w.store.record("test_restart", "platform")!!
        val id = record.getString("preparedId")
        AppModInstaller.reconcile(app, w)
        assertEquals(record.getString("outputHash"), ApkHashing.sha256(w.outputs(id).single()))
        val active = app.packageManager.packageInstaller.getSessionInfo(record.getInt("sessionId")) != null
        assertEquals(if (active) ModificationStage.INSTALLING else ModificationStage.PREPARED, w.store.getJob(id)!!.stage)
        if (!active) assertEquals(InstallationStatus.FAILED, w.store.getJob(id)!!.installerStatus)
        val oldCallback = android.app.PendingIntent.getBroadcast(app, record.getInt("sessionId"),
            android.content.Intent(app, AppModInstallReceiver::class.java).putExtra("jobId", id).putExtra("sessionId", record.getInt("sessionId")), android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_MUTABLE)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "APPMOD_PLATFORM_RESTART_CALLBACK existed=${oldCallback != null}\n") })
        val resumedSession = AppModInstaller.install(app, w, id)
        if (active) assertEquals(record.getInt("sessionId"), resumedSession)
        val inst = InstrumentationRegistry.getInstrumentation()
        var cancelled = false
        val deadline = android.os.SystemClock.elapsedRealtime() + 60000
        while (!cancelled && android.os.SystemClock.elapsedRealtime() < deadline) {
            val root = inst.uiAutomation.rootInActiveWindow
            if (root?.packageName?.toString()?.contains("packageinstaller") == true) {
                Thread.sleep(500)
                cancelled = inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Cancel")?.any { it.text?.toString() == "Cancel" && it.isEnabled && it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) } == true
            }
            if (!cancelled) kotlinx.coroutines.delay(150)
        }
        assertTrue("Resumed system confirmation Cancel not pressed", cancelled)
        while (w.store.getJob(id)!!.installerStatus != InstallationStatus.CANCELLED && android.os.SystemClock.elapsedRealtime() < deadline) kotlinx.coroutines.delay(100)
        assertEquals(ModificationStage.PREPARED, w.store.getJob(id)!!.stage)
        assertEquals(InstallationStatus.CANCELLED, w.store.getJob(id)!!.installerStatus)
        assertEquals(record.getString("outputHash"), ApkHashing.sha256(w.outputs(id).single()))
        inst.sendStatus(0, android.os.Bundle().apply { putString("stream", "APPMOD_PLATFORM_SESSION_RESTART_PASS previousSession=${record.getInt("sessionId")} resumedSession=$resumedSession wasActive=$active\n") })
    }

    @Test fun beginRealPreparationForHostProcessDeath() = runBlocking {
        val w = app.appModWorkflow
        val source = w.artifacts.capture(listOf({ app.assets.open("app-control/appmod-gallery-fixture.apk") }), SourceKind.IMPORTED_APK, "Owned real process-death fixture")
        val report = w.analyze(source.id)
        val target = w.suggestions(report.analysisId).single { it.title.contains("gallery_caption") }
        val preview = w.preview(report.analysisId, JSONArray().put(JSONObject().put("suggestionId", target.suggestionId).put("replacement", "Recovered real operation")))
        val id = preview.getString("previewId")
        // Hold the scheduling boundary while the real prepare operation writes PREPARING.
        // The host then kills this process. No synthetic job state is written by this test.
        val gate = AppModSandbox::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(AppModSandbox) as kotlinx.coroutines.sync.Mutex
        gate.lock()
        try {
            val worker = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async(kotlinx.coroutines.Dispatchers.IO) { w.prepare(id, w.approveFromUi(id, preview.getString("digest"))) }
            kotlinx.coroutines.withTimeout(10000) { while (w.store.getJob(id)!!.stage != ModificationStage.PREPARING) kotlinx.coroutines.delay(10) }
            w.store.putRecord("test_restart", "real_preparing", JSONObject().put("jobId", id).put("sourceId", source.id).put("hashes", JSONArray(source.artifact.splitSha256)))
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "APPMOD_REAL_PREPARING_CHECKPOINT job=$id\n") })
            kotlinx.coroutines.delay(60000)
            worker.cancel()
            fail("Host did not force-stop the real PREPARING operation")
        } finally { gate.unlock() }
    }

    @Test fun recoverRealPreparationAfterHostProcessDeath() = runBlocking {
        val w = app.appModWorkflow
        val checkpoint = w.store.record("test_restart", "real_preparing")!!
        val id = checkpoint.getString("jobId")
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (w.store.getJob(id)!!.stage != ModificationStage.INTERRUPTED && android.os.SystemClock.elapsedRealtime() < deadline) kotlinx.coroutines.delay(50)
        assertEquals(ModificationStage.INTERRUPTED, w.store.getJob(id)!!.stage)
        assertNull(w.store.record("outputs", id))
        val source = w.artifacts.get(checkpoint.getString("sourceId"))
        assertEquals(checkpoint.getJSONArray("hashes").toString(), JSONArray(source.files.map(ApkHashing::sha256)).toString())
        assertNotNull(OriginalArchive(app).lookup(source.artifact.identity))
        val preview = w.store.record("preview", id)!!
        val recovered = w.prepare(id, w.approveFromUi(id, preview.getString("digest")))
        assertEquals(ModificationStage.PREPARED, recovered.stage)
        assertEquals(recovered.outputSha256, ApkHashing.sha256(w.outputs(id).single()))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "APPMOD_REAL_PREPARATION_RECOVERY_PASS job=$id\n") })
    }

}
