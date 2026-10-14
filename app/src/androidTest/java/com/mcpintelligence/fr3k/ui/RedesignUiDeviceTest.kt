package com.mcpintelligence.fr3k.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.appcontrol.ModGuideCatalogue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Actual accessibility actions on rendered Android screens; receipts retain observed controls. */
@RunWith(AndroidJUnit4::class)
class RedesignUiDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext.applicationContext as Fr3kApplication
    private val evidence = JSONArray()
    private var activity: Activity? = null
    private fun open(name: String) {
        inst.runOnMainSync { activity?.finish(); app.stopService(Intent(app, com.mcpintelligence.fr3k.hud.HudOverlayService::class.java)) }
        app.settings.update { it.copy(onboardingDone = true) }
        activity = inst.startActivitySync(Intent().setClassName(app, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(700); inst.waitForIdleSync(); runCatching { inst.uiAutomation.waitForIdle(200, 3000) }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = inst.uiAutomation.rootInActiveWindow ?: return emptyList()
        root.refresh()
        val result = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) { result.add(node); for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it) } }
        walk(root); return result
    }
    private fun nodeLabels(node: AccessibilityNodeInfo): String {
        val own = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        return (own + " " + (0 until node.childCount).joinToString(" ") { i -> node.getChild(i)?.let { nodeLabels(it) }.orEmpty() }).trim()
    }
    private fun visible(node: AccessibilityNodeInfo): Boolean { val rect = Rect(); node.getBoundsInScreen(rect); return node.isVisibleToUser && !rect.isEmpty }
    /**
     * Scroll by [span] of the screen height. The default jumps 30% per step and samples only after each
     * step, so a one-line label can pass through the viewport between two samples and never be seen —
     * which is how a present guide limit was reported as missing. Searches for short text pass a small
     * span so no line is stepped over.
     */
    private fun swipeUp(reverse: Boolean = false, span: Float = 0.30f) {
        val bounds = app.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val x = bounds.width() * 0.85f
        val centre = bounds.height() * 0.55f
        val lower = centre + bounds.height() * span / 2
        val upper = centre - bounds.height() * span / 2
        // forward = drag the finger upward, which walks down the list (original semantics preserved);
        // reverse = drag downward, which walks back up it. Getting this backwards makes every search
        // start in the wrong place, which looks exactly like a missing control.
        val from = if (reverse) upper else lower
        val to = if (reverse) lower else upper
        val time = SystemClock.uptimeMillis()
        for (i in 0..12) {
            val action = when (i) { 0 -> android.view.MotionEvent.ACTION_DOWN; 12 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
            android.view.MotionEvent.obtain(time, time + i * 25, action, x, from + (to - from) * i / 12f, 0).also {
                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                inst.uiAutomation.injectInputEvent(it, true); it.recycle()
            }
        }
    }
    private fun find(label: String, description: Boolean = false, scroll: Boolean = true, actionable: Boolean = false, span: Float = 0.30f): AccessibilityNodeInfo? {
        for (direction in 0..(if (scroll) 2 else 0)) {
            repeat(if (scroll) 20 else 1) {
                val nodes = nodes()
                nodes.sortedBy { (if (description) it.contentDescription?.toString() else it.text?.toString()) != label }.firstOrNull { visible(it) && (!actionable || clickableAncestor(it) != null) && (if (description) it.contentDescription?.toString()?.contains(label) == true else it.text?.toString()?.contains(label, true) == true) }?.let { return it }
                if (!scroll) return null
                swipeUp(reverse = direction == 1, span = span)
                SystemClock.sleep(250)
                runCatching { inst.uiAutomation.waitForIdle(100, 1000) }
            }
        }
        return null
    }
    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) { if (current.isClickable) return current; current = current.parent }
        return null
    }
    private fun click(label: String, description: Boolean = false) {
        var node = find(label, description, actionable = true) ?: error("Missing rendered control: $label")
        while (!node.isClickable && node.parent != null) node = node.parent
        val deadline = SystemClock.uptimeMillis() + 20000
        while (!node.isEnabled && SystemClock.uptimeMillis() < deadline) { SystemClock.sleep(250); node.refresh() }
        assertTrue("Enabled $label", node.isEnabled)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val r = Rect(); node.getBoundsInScreen(r)
            val time = SystemClock.uptimeMillis()
            listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
                android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, r.exactCenterX(), r.exactCenterY(), 0).also {
                    it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue(inst.uiAutomation.injectInputEvent(it, true)); it.recycle()
                }
            }
        }
        SystemClock.sleep(350); inst.waitForIdleSync(); runCatching { inst.uiAutomation.waitForIdle(150, 2000) }
        evidence.put(JSONObject().put("action", label).put("clicked", true))
    }
    private fun save(name: String) {
        val directory = File(app.filesDir, "ui-audit").apply { mkdirs() }
        File(directory, "$name.json").writeText(evidence.toString(2))
        File(directory, "$name-nodes.json").writeText(JSONArray(nodes().map { JSONObject().put("text", it.text).put("description", it.contentDescription).put("visible", it.isVisibleToUser).put("clickable", it.isClickable) }).toString(2))
        inst.uiAutomation.takeScreenshot()?.let { bitmap -> File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        inst.runOnMainSync { activity?.finish() }
    }
    @Test fun eachGuideIsSearchableExpandableAndKeepsLimitsAndUndo() {
        try {
            ModGuideCatalogue.all(app).take(InstrumentationRegistry.getArguments().getString("guideLimit")?.toIntOrNull() ?: Int.MAX_VALUE).forEach { guide ->
                open("com.mcpintelligence.fr3k.ui.mods.ModsActivity")
                val search = find("mods-search", true) ?: error("Search unavailable")
                val editable = nodes().firstOrNull { it.isEditable && visible(it) } ?: search
                assertTrue(editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, guide.id) }))
                SystemClock.sleep(150)
                click(guide.title)
                assertNotNull("Guide expands ${guide.id}", find("mods-steps-${guide.id}", true))
                // Short one-line labels: search with a small scroll step so none is stepped over.
                assertNotNull("Guide limits ${guide.id}", find("limits — read this", span = 0.10f))
                assertNotNull("Guide undo ${guide.id}", find("undo", scroll = false) ?: find("undo", span = 0.10f))
                evidence.put(JSONObject().put("guide", guide.id).put("limits", true).put("undo", true))
            }
        } finally { save("guides") }
    }
    @Test fun guideCategoryFiltersAndRecheckAreRealControls() {
        try {
            open("com.mcpintelligence.fr3k.ui.mods.ModsActivity")
            click("mods-recheck", true)
            for (category in listOf("ALL", "INSPECT", "MODIFY", "AUTOMATE", "COMBINE", "CONNECT")) {
                // Fresh screen resets the vertical scroll, not the underlying catalogue.
                open("com.mcpintelligence.fr3k.ui.mods.ModsActivity")
                val label = "mods-filter-$category"
                find("mods-filter-ALL", true)
                var node = find(label, true, false)
                if (node == null) {
                    val horizontal = nodes().firstOrNull { it.isScrollable && run { val r = Rect(); it.getBoundsInScreen(r); r.width() > r.height() } }
                    horizontal?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    node = find(label, true, false)
                }
                assertNotNull("Filter $category", node)
                var clickable = node!!
                while (!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
                assertTrue(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                evidence.put(JSONObject().put("filter", category).put("clicked", true))
            }
        } finally { save("filters") }
    }
    @Test fun supportingScreensExposeHelpAndScrollableControlInventory() {
        val screens = listOf(
            "settings.SettingsActivity" to "Settings", "diagnostics.DiagnosticsActivity" to "Diagnostics",
            "automation.AutomationActivity" to "Automations", "clipboard.SmartClipboardActivity" to "Clipboard",
            "screenshot.ScreenshotActivity" to "Screenshot", "palette.CommandPaletteActivity" to "Palette",
            "ask.AskAboutThisActivity" to "Ask", "handoff.DeviceHandoffActivity" to "Handoff",
            "devoverlay.DeveloperOverlayActivity" to "Developer", "blackwave.BlackwaveActivity" to "BLACKWAVE",
            "onboarding.OnboardingActivity" to "Setup", "blackwave.BlackwaveSetupActivity" to "BLACKWAVE", "blackwave.BlackwaveAddDeviceActivity" to "BLACKWAVE"
        )
        try {
            screens.forEach { (screen, feature) ->
                open("com.mcpintelligence.fr3k.ui.$screen")
                click("How to use $feature")
                assertNotNull("Expanded help $feature", find("Hide $feature guide", scroll = false))
                click("Hide $feature guide")
                val controls = LinkedHashSet<String>()
                repeat(18) {
                    val current = nodes()
                    current.filter { it.isClickable && visible(it) }.forEach { node -> controls.add(nodeLabels(node).ifBlank { node.className.toString() }) }
                    current.filter { it.isScrollable && visible(it) }.maxByOrNull { val r = Rect(); it.getBoundsInScreen(r); r.height() }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(80)
                }
                evidence.put(JSONObject().put("screen", screen).put("helpExpanded", true).put("visibleControlInventory", JSONArray(controls)))
            }
        } finally { save("supporting-screens") }
    }
    @Test fun inspectionBootstrapAndDiagnosticsButtonsReportActualResults() {
        try {
            open("com.mcpintelligence.fr3k.ui.appcontrol.AppControlActivity")
            enter("app-control-target-package", "com.termux")
            click("INSPECT SELECTED APP")
            SystemClock.sleep(1500)
            assertNotNull(find("Signer SHA256"))
            click("SHOW AUTHORISED ADB BOOTSTRAP COMMAND")
            assertNotNull(find("CLASSPATH="))
            click("STOP HUD PRIVILEGED SERVICE")
            assertNotNull(find("Stop requested"))
            open("com.mcpintelligence.fr3k.ui.diagnostics.DiagnosticsActivity")
            click("RUN FULL DIAGNOSTIC")
            SystemClock.sleep(3000)
            assertTrue("Diagnostics stays alive", !activity!!.isFinishing)
            evidence.put(JSONObject().put("diagnosticOutput", JSONArray(nodes().mapNotNull { it.text?.toString() }.take(80))))
        } finally { save("inspection-diagnostics") }
    }
    private fun enter(label: String, value: String) {
        // App-owned fields are tagged by content-description (what a screen reader announces); some are
        // labelled by visible text. Try both before failing, and say exactly what was on screen if neither
        // is found — a bare "missing" error hides whether the field, the screen or the navigation failed.
        val found = find(label, description = true) ?: find(label)
        if (found == null) {
            val inventory = nodes().filter { it.className?.toString()?.contains("EditText") == true }.map { candidate ->
                JSONObject().put("text", candidate.text?.toString() ?: "").put("desc", candidate.contentDescription?.toString() ?: "")
                    .put("editable", candidate.isEditable).put("visible", visible(candidate))
            }
            val screenText = JSONArray(nodes().mapNotNull { it.text?.toString() }.take(40))
            evidence.put(JSONObject().put("missingInput", label).put("editTextInventory", JSONArray(inventory)).put("screenText", screenText))
            save("missing-input-diagnostics")
            error("Missing input $label — on-screen EditText nodes: $inventory")
        }
        var node: AccessibilityNodeInfo = found
        while (!node.isEditable && node.parent != null) node = node.parent
        assertTrue("Editable $label", node.isEditable)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        SystemClock.sleep(250)
    }
    @Test fun settingsKeysPreferencesAndContextControlsWorkAndRestore() {
        val before = app.settings.settings.value
        val contextBefore = app.fr3kCore.contextEngine.current.value
        val goKey = app.secureStore.get(before.opencodeApiKeyKey)
        val routerKey = app.secureStore.get(before.openrouterApiKeyKey)
        val modelBefore = app.openRouterProvider.selectedModel()
        try {
            open("com.mcpintelligence.fr3k.ui.settings.SettingsActivity")
            click("HUD overlay enabled", true)
            assertEquals(!before.hudEnabled, app.settings.settings.value.hudEnabled)
            click("HUD overlay enabled", true)
            for ((label, level) in listOf("LOCAL ONLY" to com.mcpintelligence.fr3k.core.ConsentLevel.LOCAL_ONLY, "PRIVATE ·" to com.mcpintelligence.fr3k.core.ConsentLevel.PRIVATE, "NORMAL ·" to com.mcpintelligence.fr3k.core.ConsentLevel.NORMAL, "RESEARCH ·" to com.mcpintelligence.fr3k.core.ConsentLevel.RESEARCH)) {
                click(label); assertEquals(level, app.settings.settings.value.consentProfile)
            }
            click("SAVE ENDPOINT")
            assertEquals(before.hermesEndpoint, app.settings.settings.value.hermesEndpoint)
            enter("OpenCode Go API key", "ui-audit-credential")
            click("settings-go-save", true)
            assertEquals("ui-audit-credential", app.secureStore.get(before.opencodeApiKeyKey))
            click("settings-go-remove", true)
            assertNull(app.secureStore.get(before.opencodeApiKeyKey))
            open("com.mcpintelligence.fr3k.ui.settings.SettingsActivity")
            enter("OpenRouter API key", "ui-audit-router")
            click("settings-router-save", true)
            assertEquals("ui-audit-router", app.secureStore.get(before.openrouterApiKeyKey))
            click("settings-router-remove", true)
            assertNull(app.secureStore.get(before.openrouterApiKeyKey))
            click("settings-router-free", true)
            click("CLEAR CONTEXT")
            assertTrue(app.fr3kCore.contextEngine.current.value.isEmpty())
        } finally {
            app.settings.update { before }
            app.fr3kCore.contextEngine.update { contextBefore }
            app.openRouterProvider.setModel(modelBefore)
            if (goKey == null) app.secureStore.remove(before.opencodeApiKeyKey) else app.secureStore.put(before.opencodeApiKeyKey, goKey)
            if (routerKey == null) app.secureStore.remove(before.openrouterApiKeyKey) else app.secureStore.put(before.openrouterApiKeyKey, routerKey)
            save("settings-controls")
        }
    }
    @Test fun blackwaveWizardAllEighteenStepsAndTransportModesAreReachable() {
        val before = app.settings.settings.value
        try {
            open("com.mcpintelligence.fr3k.ui.blackwave.BlackwaveSetupActivity")
            for ((index, step) in com.mcpintelligence.fr3k.ui.blackwave.WizardStep.entries.withIndex()) {
                assertNotNull("Step ${index + 1}: ${step.title}", find("step ${index + 1}/"))
                evidence.put(JSONObject().put("wizardStep", step.title).put("observed", true))
                if (index < 16) click("NEXT")
                else if (index == 16) click("SUMMARY →")
            }
            click("DONE")
            open("com.mcpintelligence.fr3k.ui.blackwave.BlackwaveAddDeviceActivity")
            assertNotNull(find("transport"))
        } finally { app.settings.update { before }; save("blackwave-wizard") }
    }

}
