package com.mcpintelligence.fr3k.appcontrol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.capability.appmod.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppModDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Fr3kApplication
    @Test fun isolatedImportAnalyzePreviewPrepareAndReopen() = runBlocking {
        val workflow = app.appModWorkflow
        val source = workflow.artifacts.capture(listOf({ app.assets.open("app-control/appmod-media-fixture.apk") }), SourceKind.IMPORTED_APK, "Owned acceptance fixture")
        val report = workflow.analyze(source.id)
        assertTrue(report.completed)
        val suggestions = workflow.suggestions(report.analysisId)
        val label = suggestions.single { it.title == "Customize appmod_label text" }
        val preview = workflow.preview(report.analysisId, JSONArray().put(JSONObject().put("suggestionId", label.suggestionId).put("replacement", "My media")), "side_by_side")
        try { workflow.prepare(preview.getString("previewId"), "forged"); fail("Forged approval accepted") } catch (expected: IllegalArgumentException) {}
        val token = workflow.approveFromUi(preview.getString("previewId"), preview.getString("digest"))
        val result = workflow.prepare(preview.getString("previewId"), token)
        assertEquals(ModificationStage.PREPARED, result.stage)
        assertEquals(VerificationStatus.STATIC_CHECKS_PASSED, result.verification)
        assertNotNull(OriginalArchive(app).lookup(source.artifact.identity))
        assertEquals(result, ModificationStore(app).use { it.getJob(result.jobId) })
        assertEquals("com.mcpintelligence.fr3k.patchfixture.hudmod", com.mcpintelligence.fr3k.appcontrol.analysis.ManifestAnalyzer.parse(workflow.outputs(result.jobId).single()).packageName)
        val bundle = android.os.Bundle().apply { putString("stream", "APPMOD_JOB=${result.jobId}\nAPPMOD_OUTPUT=${result.outputApkPath}\n") }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, bundle)
    }
    @Test fun expiredApprovalAndReplayAreRefusedAndPreviewSurvivesReopen() = runBlocking {
        var clock = 1000L
        val workflow = AppModWorkflow(app, app.modificationStore) { clock }
        val source = workflow.artifacts.capture(listOf({ app.assets.open("app-control/appmod-gallery-fixture.apk") }), SourceKind.IMPORTED_APK, "Owned approval fixture")
        val report = workflow.analyze(source.id)
        assertEquals(report.analysisId, workflow.analyze(source.id, report.analysisId).analysisId)
        val selected = workflow.suggestions(report.analysisId, "gallery caption").single { it.title.contains("gallery_caption") }
        val changes = JSONArray().put(JSONObject().put("suggestionId", selected.suggestionId).put("replacement", "Personal notes"))
        val preview = workflow.preview(report.analysisId, changes)
        val id = preview.getString("previewId")
        val expired = workflow.approveFromUi(id, preview.getString("digest"))
        clock += 120001
        try { workflow.prepare(id, expired); fail("Expired approval accepted") } catch (expected: IllegalArgumentException) {}
        assertNull(workflow.store.record("outputs", id))
        val token = workflow.approveFromUi(id, preview.getString("digest"))
        val result = workflow.prepare(id, token)
        assertEquals(ModificationStage.PREPARED, result.stage)
        try { workflow.prepare(id, token); fail("Approval replay accepted") } catch (expected: IllegalArgumentException) {}
        val hashes = workflow.outputs(id).map(ApkHashing::sha256)
        val again = workflow.prepare(id, workflow.approveFromUi(id, preview.getString("digest")))
        assertEquals(result, again)
        assertEquals(hashes, workflow.outputs(id).map(ApkHashing::sha256))
        ModificationStore(app).use { assertEquals(preview.toString(), it.record("preview", id).toString()) }
    }

    @Test fun installedSourceAndPublicResearchRemainBounded() = runBlocking {
        val workflow = app.appModWorkflow
        val source = workflow.artifacts.capture(listOf({ app.assets.open("app-control/appmod-gallery-fixture.apk") }), SourceKind.IMPORTED_APK, "Owned gallery acceptance fixture")
        val report = workflow.analyze(source.id)
        assertTrue(workflow.suggestions(report.analysisId).any { it.title.contains("gallery_caption") })
        AppModResearch.confirmRepository(workflow.store, report, "FossifyOrg/Gallery")
        val requests = AppModResearch.refresh(workflow.store, report) { JSONObject().put("items", JSONArray().put(JSONObject()
            .put("html_url", "https://github.com/FossifyOrg/Gallery/issues/390").put("title", "EXIF comments").put("state", "open")
            .put("created_at", "2025-01-03").put("updated_at", "2025-02-04").put("body", "Fixture response for the recorded real request."))) }
        assertEquals(1, requests.length())
        assertTrue(workflow.suggestions(report.analysisId).any { it.provenance == ProvenanceSource.COMMUNITY_REQUEST && it.readiness == Readiness.NEEDS_ADAPTER })
        assertTrue(AppModResearch.summary(report, "improve gallery", requests).toString().toByteArray().size <= 32768)
        val record = workflow.store.record("analysis_source", report.analysisId)!!
        assertEquals(source.id, record.getString("sourceId"))
        source.files.first().appendBytes(byteArrayOf(0))
        try { workflow.artifacts.get(source.id); fail("Changed source accepted") } catch (expected: IllegalArgumentException) {}
    }
}
