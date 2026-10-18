package com.mcpintelligence.fr3k.appcontrol

import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.capability.appmod.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.*

class AppModConcurrencyDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Fr3kApplication

    private fun preparedJob(id: String): ModificationJob {
        val artifact = ArtifactIdentity(com.mcpintelligence.fr3k.capability.AppIdentity("org.example.owned", 1, "a".repeat(64), listOf("b".repeat(64))), SourceKind.IMPORTED_APK, 1, "Concurrent store fixture")
        val spec = PatchSpec(id, null, artifact.fingerprint, listOf(PatchChange.ReplaceResourceString("0/7f010001@", "before", "after")), emptyList(), emptyList(), emptyList())
        return ModificationJob(id, artifact, ModificationStage.PREPARED, 1, 1, id, id, spec, "/retained-$id.apk", "c".repeat(64), VerificationStatus.STATIC_CHECKS_PASSED, InstallationStatus.NOT_ATTEMPTED, null)
    }
    private fun outputs(job: ModificationJob) = JSONObject().put("files", org.json.JSONArray().put(job.outputApkPath)).put("hashes", org.json.JSONArray().put(job.outputSha256))

    @Test fun preparedPublicationAndSuggestionReplacementFinishTogether() {
        val store = app.modificationStore
        val id = "concurrent-${UUID.randomUUID()}"
        val job = preparedJob(id)
        val report = AnalysisReport(id, ArtifactIdentity(com.mcpintelligence.fr3k.capability.AppIdentity("org.example.owned", 1, "a".repeat(64), listOf("b".repeat(64))), SourceKind.IMPORTED_APK, 1, "Concurrent store fixture"),
            1, emptySet(), listOf(FeatureEvidence("$id:label", EvidenceKind.OBSERVED, JSONObject().put("type", "string").put("name", "caption").put("value", "before").toString(), EvidenceReference.Resource("0/7f010001@"))), true, emptyList())
        store.saveAnalysis(report)
        val values = AppModSuggestions.generate(report)
        assertTrue(values.isNotEmpty())
        val executor = Executors.newFixedThreadPool(2)
        val transactionHeld = CountDownLatch(1)
        val replacing = CountDownLatch(1)
        val replacementThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        try {
            val publisher = executor.submit {
                val db = store.writableDatabase
                db.beginTransaction()
                try {
                    transactionHeld.countDown()
                    check(replacing.await(5, TimeUnit.SECONDS))
                    val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                    while (replacementThread.get().stackTrace.none { it.methodName == "waitForConnection" } && android.os.SystemClock.elapsedRealtime() < deadline) Thread.yield()
                    check(replacementThread.get().stackTrace.any { it.methodName == "waitForConnection" }) { "Replacement never queued on the held SQLite transaction" }
                    store.publishPrepared(job, outputs(job))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            val replacement = executor.submit {
                check(transactionHeld.await(5, TimeUnit.SECONDS)); replacementThread.set(Thread.currentThread()); replacing.countDown()
                store.replaceSuggestions(id, values)
            }
            publisher.get(10, TimeUnit.SECONDS); replacement.get(10, TimeUnit.SECONDS)
            assertEquals(job, store.getJob(id))
            assertEquals(job.outputSha256, store.record("outputs", id)!!.getJSONArray("hashes").getString(0))
            assertEquals(job.outputApkPath, store.record("outputs", id)!!.getJSONArray("files").getString(0))
            assertEquals(values.toSet(), store.getSuggestionsForAnalysis(id).toSet())
        } finally { executor.shutdownNow() }
    }

    @Test fun databaseFailureRollsBackPreparedOutputPublication() {
        val store = app.modificationStore
        val id = "rollback-${UUID.randomUUID()}"
        val trigger = "test_publication_" + UUID.randomUUID().toString().replace("-", "")
        val job = preparedJob(id)
        store.writableDatabase.execSQL("CREATE TEMP TRIGGER $trigger BEFORE INSERT ON job WHEN NEW.job_id = '$id' BEGIN SELECT RAISE(FAIL, 'task-owned publication failure'); END")
        try {
            assertThrows(Exception::class.java) { store.publishPrepared(job, outputs(job)) }
            assertNull(store.record("outputs", id))
            assertNull(store.getJob(id))
        } finally { store.writableDatabase.execSQL("DROP TRIGGER IF EXISTS temp.$trigger") }
    }

    @Test fun queuedPreparationCancellationCannotKillAnotherPreparation() = runBlocking {
        val w = app.appModWorkflow
        val source = w.artifacts.capture(listOf({ app.assets.open("app-control/appmod-gallery-fixture.apk") }), SourceKind.IMPORTED_APK, "Concurrent preparation fixture")
        val report = w.analyze(source.id)
        val selected = w.suggestions(report.analysisId).single { it.title.contains("gallery_caption") }
        fun preview(value: String) = w.preview(report.analysisId, org.json.JSONArray().put(JSONObject().put("suggestionId", selected.suggestionId).put("replacement", value)))
        val first = preview("Owner"); val second = preview("Queued")
        val root = File(app.cacheDir, "prepare-cancellation-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val blocker = async(start = CoroutineStart.UNDISPATCHED) {
                AppModSandbox.run(app, List(32) { source.files.single() }, listOf(File(root, "metadata.json")), JSONObject().put("operation", "metadata"))
            }
            val owner = async(Dispatchers.IO) { w.prepare(first.getString("previewId"), w.approveFromUi(first.getString("previewId"), first.getString("digest"))) }
            withTimeout(30000) { while (w.store.getJob(first.getString("previewId"))!!.stage != ModificationStage.PREPARING) delay(10) }
            val queued = async(start = CoroutineStart.UNDISPATCHED) { w.prepare(second.getString("previewId"), w.approveFromUi(second.getString("previewId"), second.getString("digest"))) }
            queued.cancelAndJoin()
            withTimeout(60000) { blocker.await(); assertEquals(ModificationStage.PREPARED, owner.await().stage) }
            assertEquals(ModificationStage.PREVIEWING, w.store.getJob(second.getString("previewId"))!!.stage)
            assertNull(w.store.record("outputs", second.getString("previewId")))
            assertEquals(source.artifact.splitSha256, w.artifacts.get(source.id).files.map(ApkHashing::sha256))
            val id = first.getString("previewId")
            val token = w.approveFromUi(id, first.getString("digest"))
            val duplicate = async { runCatching { w.prepare(id, token) } }
            val duplicateAgain = async { runCatching { w.prepare(id, token) } }
            val results = listOf(duplicate.await(), duplicateAgain.await())
            assertEquals(1, results.count { it.isSuccess }); assertEquals(1, results.count { it.isFailure })
            assertEquals(1, w.outputs(id).size)
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellingQueuedSandboxDoesNotKillOwner() = runBlocking {
        val root = File(app.cacheDir, "cancellation-${UUID.randomUUID()}").apply { mkdirs() }
        val fixture = File(root, "input.apk")
        app.assets.open("app-control/appmod-media-fixture.apk").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        try {
            val ownerOutput = File(root, "owner.json")
            // Undispatched execution enters the mutex before the second operation queues.
            val owner = async(start = CoroutineStart.UNDISPATCHED) {
                AppModSandbox.run(app, listOf(fixture), listOf(ownerOutput), JSONObject().put("operation", "metadata"))
            }
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                AppModSandbox.run(app, listOf(fixture), listOf(File(root, "queued.json")), JSONObject().put("operation", "metadata"))
            }
            queued.cancelAndJoin()
            withTimeout(30000) { owner.await() }
            assertEquals("com.mcpintelligence.fr3k.patchfixture", org.json.JSONArray(ownerOutput.readText()).getJSONObject(0).getString("package"))
            assertFalse(File(root, "queued.json").exists())
            val retry = File(root, "retry.json")
            val cancelledOwner = async(start = CoroutineStart.UNDISPATCHED) {
                AppModSandbox.run(app, List(32) { fixture }, listOf(File(root, "cancelled.json")), JSONObject().put("operation", "metadata"))
            }
            withTimeout(30000) { while (!File(root, "cancelled.json").exists()) { check(!cancelledOwner.isCompleted); delay(10) } }
            cancelledOwner.cancelAndJoin()
            withTimeout(30000) { AppModSandbox.run(app, listOf(fixture), listOf(retry), JSONObject().put("operation", "metadata")) }
            assertTrue(retry.isFile)
            assertEquals(ApkHashing.sha256(fixture), app.assets.open("app-control/appmod-media-fixture.apk").use { java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) } })
        } finally { root.deleteRecursively() }
    }
}
