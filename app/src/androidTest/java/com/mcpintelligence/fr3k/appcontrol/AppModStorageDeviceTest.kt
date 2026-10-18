package com.mcpintelligence.fr3k.appcontrol

import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.capability.appmod.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppModStorageDeviceTest {
    @Test fun insufficientSpaceFailsBeforePublishingAndRetainsOriginal() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Fr3kApplication
        val w = app.appModWorkflow
        val source = w.artifacts.capture(listOf({ app.assets.open("app-control/appmod-gallery-fixture.apk") }), SourceKind.IMPORTED_APK, "Owned low-space fixture")
        val report = w.analyze(source.id)
        val selected = w.suggestions(report.analysisId).single { it.title.contains("gallery_caption") }
        val preview = w.preview(report.analysisId, JSONArray().put(JSONObject().put("suggestionId", selected.suggestionId).put("replacement", "Low space")))
        val id = preview.getString("previewId")
        val reservation = File(app.cacheDir, "appmod-space-reservation")
        check(!reservation.exists()) { "Task reservation from a failed run requires harness cleanup" }
        try {
            val available = reservation.parentFile!!.usableSpace
            check(available < 12L * 1024 * 1024 * 1024) { "Test allocation exceeds task AVD budget" }
            // Reserve only this task file, retaining 192 MiB for Android and receipt writes.
            reservation.outputStream().use { android.system.Os.posix_fallocate(it.fd, 0, (available - 192L * 1024 * 1024).coerceAtLeast(1)) }
            check(reservation.parentFile!!.usableSpace < 256L * 1024 * 1024)
            var failure: Exception? = null
            try { w.prepare(id, w.approveFromUi(id, preview.getString("digest"))) } catch (expected: Exception) { failure = expected }
            assertTrue(failure?.message.orEmpty().contains("Insufficient space"))
            assertEquals(ModificationStage.FAILED, w.store.getJob(id)!!.stage)
            assertNull(w.store.record("outputs", id))
            assertEquals(source.artifact.splitSha256, w.artifacts.get(source.id).files.map(ApkHashing::sha256))
            assertNotNull(OriginalArchive(app).lookup(source.artifact.identity))
        } finally { reservation.delete() }
    }
}
