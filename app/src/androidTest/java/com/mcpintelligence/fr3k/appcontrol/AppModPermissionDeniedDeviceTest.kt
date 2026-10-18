package com.mcpintelligence.fr3k.appcontrol

import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.capability.appmod.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test

/** Host initializes denied permission before instrumentation: changing it live kills the app. */
class AppModPermissionDeniedDeviceTest {
    @Test fun missingPermissionDoesNotCreateInstallerSession() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Fr3kApplication
        assertFalse(app.packageManager.canRequestPackageInstalls())
        val w = app.appModWorkflow
        val source = w.artifacts.capture(listOf({ app.assets.open("app-control/appmod-gallery-fixture.apk") }), SourceKind.IMPORTED_APK, "Owned denied permission fixture")
        val report = w.analyze(source.id)
        val target = w.suggestions(report.analysisId).single { it.title.contains("gallery_caption") }
        val preview = w.preview(report.analysisId, JSONArray().put(JSONObject().put("suggestionId", target.suggestionId).put("replacement", "Permission notes")))
        val id = preview.getString("previewId")
        w.prepare(id, w.approveFromUi(id, preview.getString("digest")))
        try { AppModInstaller.install(app, w, id); fail("Install permission bypassed") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("Allow installation")) }
        assertNull(w.store.record("install_session", id))
        assertEquals(ModificationStage.PREPARED, w.store.getJob(id)!!.stage)
        assertTrue(w.outputs(id).all { it.isFile })
    }
}
