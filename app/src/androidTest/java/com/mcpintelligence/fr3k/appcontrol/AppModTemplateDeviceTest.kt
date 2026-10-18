package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.capability.appmod.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

class AppModTemplateDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Fr3kApplication
    @Test fun fixedPayloadCleansUrlsAndPersistsPerContextKey() {
        // Test-only loading of HUD's reviewed helper asset. Production never loads selected APK code.
        val loader = dalvik.system.InMemoryDexClassLoader(ByteBuffer.wrap(app.assets.open("appmod/classes.dex").use { it.readBytes() }), app.classLoader)
        val helper = loader.loadClass("com.fr3k.appmodpayload.Helpers")
        val clean = helper.getMethod("cleanUrl", String::class.java)
        assertEquals("https://example.org/a?keep=1#part", clean.invoke(null, "https://example.org/a?utm_source=test&keep=1&fbclid=x#part"))
        assertEquals("https://example.org/a#part", clean.invoke(null, "https://example.org/a?utm_source=test#part"))
        assertEquals("mailto:owned@example.org", clean.invoke(null, "mailto:owned@example.org"))
        val persist = helper.getMethod("persist", Context::class.java, String::class.java, String::class.java)
        val a = "owned-test-${UUID.randomUUID()}"; val b = "$a-other"
        try {
            assertEquals("first", persist.invoke(null, app, a, "first"))
            assertEquals("first", persist.invoke(null, app, a, "second"))
            assertEquals("second", persist.invoke(null, app, b, "second"))
            assertEquals("first", persist.invoke(null, app.createPackageContext(app.packageName, 0), a, "third"))
        } finally { app.getSharedPreferences("hud_appmod_defaults", 0).edit().remove(a).remove(b).commit() }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").setPackage(app.packageName)
        assertNotNull(app.packageManager.resolveActivity(intent, 0))
        val inst = InstrumentationRegistry.getInstrumentation()
        val shared = "Owned reviewed helper share"
        assertEquals(shared, helper.getMethod("share", Context::class.java, String::class.java).invoke(null, app, shared))
        var observed = false
        val until = android.os.SystemClock.elapsedRealtime() + 30000
        while (!observed && android.os.SystemClock.elapsedRealtime() < until) {
            inst.runOnMainSync {
                observed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .any { it.intent.action == android.content.Intent.ACTION_SEND && it.intent.getStringExtra(android.content.Intent.EXTRA_TEXT) == shared }
            }
            if (!observed) Thread.sleep(100)
        }
        assertTrue("Reviewed helper did not dispatch its actual HUD share intent", observed)
    }
    @Test fun insertedShareHelperDispatchesThePatchedAppsActualReturnedValue() = runBlocking {
        val w = app.appModWorkflow
        val source = w.artifacts.capture(listOf({ app.assets.open("app-control/appmod-media-fixture.apk") }), SourceKind.IMPORTED_APK, "Owned inserted-share fixture")
        val report = w.analyze(source.id)
        val target = report.evidence.single { it.evidenceId.contains(":helper:") && (it.reference as? EvidenceReference.DexDescriptor)?.descriptor?.contains("->contextDefault(") == true }
        val suggestion = AppModSuggestions.generate(report).single { it.evidenceIds == listOf(target.evidenceId) }
        w.store.saveSuggestion(suggestion)
        val preview = w.preview(report.analysisId, JSONArray().put(JSONObject().put("suggestionId", suggestion.suggestionId).put("replacement", "hud.share")), "side_by_side")
        val id = preview.getString("previewId")
        w.prepare(id, w.approveFromUi(id, preview.getString("digest")))
        val inst = InstrumentationRegistry.getInstrumentation()
        fun uninstall() = inst.uiAutomation.executeShellCommand("pm uninstall com.mcpintelligence.fr3k.patchfixture.hudmod").use { fd -> java.io.FileInputStream(fd.fileDescriptor).bufferedReader().use { it.readText() } }
        uninstall()
        try {
            AppModInstaller.install(app, w, id)
            val deadline = android.os.SystemClock.elapsedRealtime() + 90000
            var confirmed = false
            while (!confirmed && android.os.SystemClock.elapsedRealtime() < deadline) {
                val root = inst.uiAutomation.rootInActiveWindow
                if (root?.packageName?.toString()?.contains("packageinstaller") == true) {
                    confirmed = root.findAccessibilityNodeInfosByText("Install").any { it.text?.toString() == "Install" && it.isEnabled && it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) }
                }
                if (!confirmed) kotlinx.coroutines.delay(150)
            }
            assertTrue("Inserted-share fixture installer confirmation missing", confirmed)
            while (w.store.getJob(id)!!.installerStatus != InstallationStatus.INSTALLED && android.os.SystemClock.elapsedRealtime() < deadline) kotlinx.coroutines.delay(150)
            assertTrue(AppModInstaller.verifyInstalled(app, w, id))
            app.startActivity(android.content.Intent().setComponent(android.content.ComponentName("com.mcpintelligence.fr3k.patchfixture.hudmod", "com.mcpintelligence.fr3k.patchfixture.ModLabActivity")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            var received = false
            while (!received && android.os.SystemClock.elapsedRealtime() < deadline) {
                inst.runOnMainSync {
                    received = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .any { it.intent.action == android.content.Intent.ACTION_SEND && it.intent.getStringExtra(android.content.Intent.EXTRA_TEXT) == "normal" }
                }
                if (!received) kotlinx.coroutines.delay(100)
            }
            assertTrue("Patched helper call did not share its actual returned string", received)
            inst.sendStatus(0, android.os.Bundle().apply { putString("stream", "APPMOD_INSERTED_SHARE_BEHAVIOR_PASS job=$id\n") })
        } finally { uninstall() }
    }

    @Test fun exactOwnedHookPreparesWithSignatureBypassDisabled() = runBlocking {
        val w = app.appModWorkflow
        val source = w.artifacts.capture(listOf({ app.assets.open("app-control/owned-fixture.apk") }), SourceKind.IMPORTED_APK, "Committed owned hook fixture")
        val report = w.analyze(source.id)
        val hook = w.suggestions(report.analysisId).single { it.suggestionId.endsWith(":owned-hook") }
        val preview = w.preview(report.analysisId, JSONArray().put(JSONObject().put("suggestionId", hook.suggestionId).put("replacement", "{}")), "same_package")
        val id = preview.getString("previewId")
        assertEquals(ModificationStage.PREPARED, w.prepare(id, w.approveFromUi(id, preview.getString("digest"))).stage)
        java.util.zip.ZipFile(w.outputs(id).single()).use { zip ->
            val config = JSONObject(zip.getInputStream(zip.getEntry("assets/lspatch/config.json")).bufferedReader().use { it.readText() })
            assertFalse(config.getBoolean("useManager")); assertEquals(0, config.getInt("sigBypassLevel"))
        }
        val inst = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = inst.uiAutomation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).bufferedReader().use { it.readText() } }
        shell("pm uninstall com.mcpintelligence.fr3k.patchfixture")
        try {
            AppModInstaller.install(app, w, id)
            val deadline = android.os.SystemClock.elapsedRealtime() + 60000
            var confirmed = false
            while (!confirmed && android.os.SystemClock.elapsedRealtime() < deadline) {
                val root = inst.uiAutomation.rootInActiveWindow
                if (root?.packageName?.toString()?.contains("packageinstaller") == true) {
                    confirmed = root.findAccessibilityNodeInfosByText("Install").any { it.text?.toString() == "Install" && it.isEnabled && it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) }
                }
                if (!confirmed) Thread.sleep(150)
            }
            assertTrue("Owned hook system confirmation missing", confirmed)
            while (w.store.getJob(id)!!.installerStatus == InstallationStatus.INSTALLER_OPENED && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(150)
            assertEquals(InstallationStatus.INSTALLED, w.store.getJob(id)!!.installerStatus)
            FixtureHookDeviceTest().ownedMethodChangesTrueFalseTrueInOneProcess()
        } finally { shell("pm uninstall com.mcpintelligence.fr3k.patchfixture") }
    }
}
