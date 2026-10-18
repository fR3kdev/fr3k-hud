package com.mcpintelligence.fr3k.appcontrol

import android.content.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.capability.appmod.*
import com.mcpintelligence.fr3k.protocol.AgentAskResponse
import com.mcpintelligence.fr3k.protocol.AgentAskRequest
import com.mcpintelligence.fr3k.core.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppModJourneyDeviceTest {
    private val BUILD_MODEL = android.os.Build.MODEL
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Fr3kApplication
    private fun status(message: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "$message\n") })
    private fun waitUntil(timeout: Long = 20000, predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; Thread.sleep(100) }
        fun describe(node: AccessibilityNodeInfo?, depth: Int = 0): String = if (node == null || depth > 30) "" else
            "${node.text}|${node.contentDescription}|enabled=${node.isEnabled}|click=${node.isClickable}\n" + (0 until node.childCount).joinToString("") { describe(node.getChild(it), depth + 1) }
        diagnose()
        // The tree dump is chunked and logcat drops most of it, so the page's text is also logged as one
        // compact line: a failure then says whether the expected label is absent or merely unreachable.
        android.util.Log.i("APPMODDBG", "page=${pageText().take(3000)}")
        assertTrue("Condition not reached: " + describe(instrumentation.uiAutomation.rootInActiveWindow), predicate())
    }
    /** Every window's text and descriptions as one line, for diagnosing a missing label. */
    private fun pageText(): String {
        val parts = ArrayList<String>()
        windows().forEach { root ->
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 30 || parts.size > 300) return
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let { parts += it }
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { parts += it }
                for (index in 0 until node.childCount) walk(node.getChild(index), depth + 1)
            }
            walk(root, 0)
        }
        return parts.distinct().joinToString(" | ")
    }
    /** Full bounded tree with bounds, chunked to logcat: the assertion message alone was not diagnostic enough. */
    private fun diagnose() {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
        val window = android.graphics.Rect(); root.getBoundsInScreen(window)
        val bounds = android.graphics.Rect()
        val lines = ArrayList<String>()
        val packageName = root.packageName?.toString() ?: "?"
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || lines.size > 400) return
            node.getBoundsInScreen(bounds)
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.isNotBlank() || desc.isNotBlank()) lines += "d$depth ${node.className} text='$text' desc='$desc' enabled=${node.isEnabled} click=${node.isClickable} visible=${node.isVisibleToUser} bounds=$bounds"
            for (index in 0 until node.childCount) walk(node.getChild(index), depth + 1)
        }
        walk(root, 0)
        android.util.Log.i("APPMODDBG", "root=$packageName window=$window nodes=${lines.size}")
        lines.forEach { line -> android.util.Log.i("APPMODDBG", line.take(900)) }
    }
    private fun scroll(node: AccessibilityNodeInfo?, forward: Boolean = true): Boolean {
        if (node == null) return false
        if (node.isScrollable && node.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        return (0 until node.childCount).any { scroll(node.getChild(it), forward) }
    }
    private fun matchNode(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEnabled && (node.text?.toString()?.equals(text, true) == true || node.contentDescription?.toString()?.equals(text, true) == true)) return node
        for (index in 0 until node.childCount) matchNode(node.getChild(index), text)?.let { return it }
        return null
    }
    /**
     * Manual child traversal misses nodes on Compose views at large font scales, so the platform search runs
     * first; the exact-text filter keeps "Install" from matching the HUD's own "Review in Android installer".
     */
    private fun findTarget(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val platform = root.findAccessibilityNodeInfosByText(text).orEmpty()
            .filter { it.isEnabled && it.text?.toString()?.equals(text, true) == true }
        return platform.firstOrNull { it.isVisibleToUser } ?: platform.firstOrNull() ?: matchNode(root, text)
    }
    /** A cheap position fingerprint of the active page: text and bounds, so a real scroll is observable. */
    private fun pageStamp(): Int {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return 0
        val text = StringBuilder()
        val bounds = android.graphics.Rect()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30) return
            node.getBoundsInScreen(bounds)
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { text.append(it).append(bounds).append('|') }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { text.append(it).append('|') }
            for (index in 0 until node.childCount) walk(node.getChild(index), depth + 1)
        }
        walk(root, 0)
        return text.toString().hashCode()
    }
    /**
     * The accessibility scroll is tried first, but it is only accepted when the page actually moved: an
     * editable text field also reports itself as scrollable and acknowledges the action without scrolling
     * the page. When it does not move, a real timed gesture is dispatched instead.
     */
    private fun swipe(forward: Boolean) {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
        val before = pageStamp()
        if (scroll(root, forward)) {
            Thread.sleep(200)
            if (pageStamp() != before) { Thread.sleep(150); return }
        }
        val window = android.graphics.Rect(); root.getBoundsInScreen(window)
        val x = window.exactCenterX()
        val from = window.top + window.height() * (if (forward) 0.8f else 0.2f)
        val to = window.top + window.height() * (if (forward) 0.2f else 0.8f)
        val start = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = android.view.MotionEvent.obtain(start, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        send(android.view.MotionEvent.ACTION_DOWN, from)
        repeat(8) { step ->
            Thread.sleep(20)
            send(android.view.MotionEvent.ACTION_MOVE, from + (to - from) * (step + 1) / 8f)
        }
        Thread.sleep(20)
        send(android.view.MotionEvent.ACTION_UP, to)
    }
    private fun click(text: String, timeout: Long = 30000) {
        android.util.Log.i("APPMODDBG", "click '$text' timeout=$timeout")
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        // A target that is scrolled far away can be dropped from the accessibility tree entirely, so a missing
        // node sweeps the page instead of scrolling away from it forever.
        val systemControl = text in setOf("Cancel", "Install", "Update", "Done")
        var missing = 0
        var tries = 0
        var forward = true
        var stalled = 0
        var backedOut = false
        waitUntil(timeout) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            tries++
            // A system dialog (package installer) is not always the active window, so every window is searched.
            val node = (listOfNotNull(root) + instrumentation.uiAutomation.windows.mapNotNull { it.root })
                .firstNotNullOfOrNull { findTarget(it, text) }
            if (node == null) {
                // Only the HUD's own scrollable pages are swept. A system dialog (package installer) has fixed
                // buttons, and an injected swipe there was measured dismissing the confirmation.
                missing++
                if (systemControl || root?.packageName?.toString() != app.packageName) {
                    // The product has controls that hand off to Settings (the removal review), so a wait that
                    // finds Settings in front instead of the product is recovered with one back action rather
                    // than timing out; the picker and installer windows are legitimately not the product's.
                    if (!systemControl && !backedOut && root?.packageName?.toString() == "com.android.settings") {
                        backedOut = true
                        android.util.Log.i("APPMODDBG", "click '$text' found Settings in front; returning to the product")
                        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                        Thread.sleep(700)
                    } else Thread.sleep(250)
                    return@waitUntil false
                }
                // Review pages grow downward; sweep down, and only re-anchor (one swipe up) if the page stopped
                // changing, so a long 200%-font page is never abandoned by an early direction flip.
                if (stalled >= 4) { stalled = 0; forward = !forward }
                val before = pageStamp()
                swipe(forward)
                Thread.sleep(250)
                if (before == pageStamp()) stalled++ else stalled = 0
                if (tries % 8 == 0) android.util.Log.i("APPMODDBG", "iter=$tries missing=$missing stalled=$stalled")
                return@waitUntil false
            }
            // The app window minus the system bars. A label's own box can be a one-line sliver clipped by the
            // navigation bar, and a tap there is delivered to the system instead of the control: measured as
            // silently dropped taps on the basket switches, the radio rows and the review buttons.
            val safe = safeViewport()
            var bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
            var control: AccessibilityNodeInfo? = node
            while (control != null && !control.isClickable) control = control.parent
            if (control != null) control.getBoundsInScreen(bounds)
            // Compose can acknowledge ACTION_CLICK on an off-screen, stale semantics node
            // without invoking the current button. Bring the target into the viewport first.
            if (bounds.isEmpty || !node.isVisibleToUser || !safe.contains(bounds.exactCenterX().toInt(), bounds.exactCenterY().toInt())) {
                if (!systemControl) swipe(bounds.isEmpty || bounds.exactCenterY() >= safe.exactCenterY())
                Thread.sleep(250)
                return@waitUntil false
            }
            // A tap injected while the page is still settling from a swipe is consumed stopping the scroll, and
            // a held node's bounds are a snapshot from when it was fetched: the control is re-found after the
            // page stops moving, because tapping stale bounds was measured landing on the neighbouring control
            // and following its action instead (the verification step opened Settings instead of confirming).
            settle()
            val fresh = (listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow) + instrumentation.uiAutomation.windows.mapNotNull { it.root })
                .firstNotNullOfOrNull { findTarget(it, text) }
            if (fresh == null || !fresh.isVisibleToUser) { Thread.sleep(200); return@waitUntil false }
            bounds = android.graphics.Rect(); fresh.getBoundsInScreen(bounds)
            control = fresh
            while (control != null && !control.isClickable) control = control.parent
            if (control != null) control.getBoundsInScreen(bounds)
            if (bounds.isEmpty || !safe.contains(bounds.exactCenterX().toInt(), bounds.exactCenterY().toInt())) {
                if (!systemControl) swipe(bounds.isEmpty || bounds.exactCenterY() >= safe.exactCenterY())
                Thread.sleep(250)
                return@waitUntil false
            }
            // DocumentsUI rows expose a clickable ancestor; its action selects the URI even when
            // the filename text's pointer bounds overlap the roots drawer during a transition.
            if (node.packageName?.toString()?.contains("documentsui", ignoreCase = true) == true) {
                if (control?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    Thread.sleep(250)
                    return@waitUntil true
                }
            }
            // Dispatch a real pointer gesture at the owning control's centre. Some system dialogs execute
            // ACTION_CLICK yet report false while closing, and off-screen Compose nodes can report true
            // without invoking their callback. Result assertions remain in each scenario.
            val now = android.os.SystemClock.uptimeMillis()
            val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
            android.util.Log.i("APPMODDBG", "tap '$text' at=$x,$y control=${control?.className} enabled=${control?.isEnabled} window=${control?.windowId} bounds=$bounds safe=$safe")
            val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            val up = android.view.MotionEvent.obtain(now, now + 60, android.view.MotionEvent.ACTION_UP, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try {
                check(instrumentation.uiAutomation.injectInputEvent(down, false))
                Thread.sleep(60)
                check(instrumentation.uiAutomation.injectInputEvent(up, false))
                Thread.sleep(150)
                true
            } finally { down.recycle(); up.recycle() }
        }
    }
    private fun edit(label: String, value: String) {
        fun setText(target: AccessibilityNodeInfo?): Boolean = target?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }) == true
        fun editableFields(): List<AccessibilityNodeInfo> = allNodes()
            .filter { it.isEditable || it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
        // The visible label is a child of the field, so the field is found by the label inside its own
        // subtree; the label node itself is not editable and used to make this helper give up.
        fun labelledField(): AccessibilityNodeInfo? {
            fun holdsLabel(node: AccessibilityNodeInfo?, depth: Int): Boolean {
                if (node == null || depth > 30) return false
                if (node.text?.toString() == label || node.contentDescription?.toString() == label) return true
                return (0 until node.childCount).any { holdsLabel(node.getChild(it), depth + 1) }
            }
            val fields = editableFields()
            return fields.firstOrNull { it.contentDescription?.toString() == label }
                ?: fields.firstOrNull { holdsLabel(it, 0) }
                ?: fields.firstOrNull { it.isFocused }
                ?: fields.singleOrNull()
        }
        click(label, 60000)
        waitUntil(30000) { setText(labelledField()) }
        Thread.sleep(500)
        if (instrumentation.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    private fun visible(text: String): Boolean {
        fun contains(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isVisibleToUser && (node.text?.toString()?.contains(text, true) == true || node.contentDescription?.toString()?.contains(text, true) == true)) return true
            return (0 until node.childCount).any { contains(node.getChild(it)) }
        }
        return contains(instrumentation.uiAutomation.rootInActiveWindow)
    }

    /** Every window's tree: a system dialog is not always the active window. */
    private fun windows(): List<AccessibilityNodeInfo> =
        listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow) + instrumentation.uiAutomation.windows.mapNotNull { it.root }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30) return
            nodes += node
            for (index in 0 until node.childCount) walk(node.getChild(index), depth + 1)
        }
        windows().forEach { walk(it, 0) }
        return nodes
    }

    /** The window area that receives touches: the app window minus the system bars. */
    private fun safeViewport(): android.graphics.Rect {
        val manager = app.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = manager.currentWindowMetrics
        val bars = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
        return android.graphics.Rect(bars.left, bars.top, metrics.bounds.width() - bars.right, metrics.bounds.height() - bars.bottom)
    }

    /**
     * Waits for the page to stop moving. A tap injected while the page is still settling from a swipe is
     * consumed stopping the scroll instead of pressing the control: measured as silently dropped taps
     * immediately after a sweep brought the target into view.
     */
    private fun settle(quiet: Long = 350, timeout: Long = 3000) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeout
        var previous = pageStamp()
        var stillSince = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(120)
            val current = pageStamp()
            if (current != previous) { previous = current; stillSince = android.os.SystemClock.elapsedRealtime() }
            else if (android.os.SystemClock.elapsedRealtime() - stillSince >= quiet) return
        }
    }

    /** A product message is evidence of a control's effect, so it is observed rather than clicked. */
    private fun shown(text: String, timeout: Long = 30000, sweep: Boolean = true): Boolean {
        // A manual tree walk is the effective search here: the platform text search was measured returning
        // nothing for a message that the walk finds in the same window.
        fun found() = allNodes().any { node ->
            node.text?.toString()?.contains(text, true) == true || node.contentDescription?.toString()?.contains(text, true) == true }
        val deadline = android.os.SystemClock.elapsedRealtime() + timeout
        var forward = true
        var stalled = 0
        var sweeps = 0
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (found()) return true
            // Off-viewport Compose nodes leave the accessibility tree, so the page is swept while looking.
            if (!sweep || instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != app.packageName) { Thread.sleep(150); continue }
            val before = pageStamp()
            swipe(forward)
            Thread.sleep(150)
            sweeps++
            if (before == pageStamp()) { if (++stalled >= 3) { stalled = 0; forward = !forward } } else stalled = 0
        }
        val result = found()
        if (!result) {
            // Scroll back to the top and report the page's own text, so a refusal is visible in evidence.
            var previous = 0
            var current = pageStamp()
            while (current != previous) { previous = current; swipe(false); Thread.sleep(120); current = pageStamp() }
            val visible = allNodes().mapNotNull { node -> node.text?.toString()?.takeIf { it.isNotBlank() } }.joinToString(" | ")
            android.util.Log.i("APPMODDBG", "shown '$text' missing; page='${visible.take(3000)}'")
        }
        android.util.Log.i("APPMODDBG", "shown '$text' found=$result sweeps=$sweeps forward=$forward")
        return result
    }

    /**
     * Drives a real control until its observable effect appears. A tap dropped by a transient
     * re-layout was measured being accepted silently, so the control is re-driven while the
     * effect is still missing; the effect assertion itself stays in the caller.
     */
    private fun clickUntil(text: String, timeout: Long = 60000, attempts: Int = 4, effect: () -> Boolean) {
        var tries = 0
        waitUntil(timeout) {
            if (effect()) return@waitUntil true
            if (tries++ < attempts) { runCatching { click(text, 15000) }; Thread.sleep(400) }
            effect()
        }
    }

    /** The persisted draft for an analysis: the real product state the basket controls write. */
    private fun draftSelection(analysisId: String): JSONObject? =
        app.modificationStore.record("draft", analysisId)?.optString("selection")?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun draftMode(analysisId: String): String? = app.modificationStore.record("draft", analysisId)?.optString("mode")

    @Test fun actualReviewInstallerAndModifiedBehavior() = runBlocking {
        val workflow = app.appModWorkflow
        val source = workflow.artifacts.capture(listOf({ app.assets.open("app-control/appmod-media-fixture.apk") }), SourceKind.IMPORTED_APK, "Owned runtime journey")
        val report = workflow.analyze(source.id)
        // Use the actual suggestion compiler, with goals that surface the requested typed targets.
        val selections = JSONArray()
        fun add(e: FeatureEvidence, value: String) {
            val suggestion = AppModSuggestions.generate(report).single { it.evidenceIds == listOf(e.evidenceId) }
            workflow.store.saveSuggestion(suggestion)
            selections.put(JSONObject().put("suggestionId", suggestion.suggestionId).put("replacement", value))
        }
        add(report.evidence.single { it.reference is EvidenceReference.Resource && JSONObject(it.feature).optString("name") == "appmod_label" }, "Patched media")
        add(report.evidence.single { it.reference is EvidenceReference.Resource && JSONObject(it.feature).optString("name") == "appmod_accent" }, "ff112233")
        add(report.evidence.single { (it.reference as? EvidenceReference.DexDescriptor)?.descriptor?.contains("->playbackDefault()I") == true && it.evidenceId.contains(":dex:") }, "5")
        add(report.evidence.single { (it.reference as? EvidenceReference.DexDescriptor)?.descriptor?.contains("->mediaUrl()") == true && it.evidenceId.contains(":helper:") }, "url.clean")
        add(report.evidence.single { (it.reference as? EvidenceReference.DexDescriptor)?.descriptor?.contains("->contextDefault(") == true && it.evidenceId.contains(":helper:") }, "preference.persist")
        val updating = InstrumentationRegistry.getArguments().getString("appmodUpdate") == "true"
        if (updating) add(report.evidence.single { (it.reference as? EvidenceReference.DexDescriptor)?.descriptor?.contains("->contextDefault(") == true && it.evidenceId.contains(":dex:") }, "changed-default")
        val panelId = com.mcpintelligence.fr3k.appcontrol.analysis.ApkAccess.open(source.files.single()).use { access ->
            access.module.tableBlock.resources.asSequence().single { it.name == "appmod_panel" }.resourceId
        }
        val panel = report.evidence.single { (it.reference as? EvidenceReference.Resource)?.resourceId?.endsWith("#%08x".format(java.util.Locale.ROOT, panelId)) == true }
        add(panel, "8")
        val preview = workflow.preview(report.analysisId, selections, "side_by_side")
        val id = preview.getString("previewId")
        val apiResult = AppModApi(app).call("request_app_mod_preparation", JSONObject().put("preview_id", id))
        assertEquals("AWAITING_USER_REVIEW", apiResult.getString("status"))
        assertNull(workflow.store.record("outputs", id))
        clickUntil("Approve exact changes and prepare copy") { workflow.store.getJob(id)?.stage in setOf(ModificationStage.PREPARED, ModificationStage.FAILED) }
        assertEquals(workflow.store.getJob(id)?.error, ModificationStage.PREPARED, workflow.store.getJob(id)?.stage)
        click("Review in Android installer")
        // Wait for the system installer dialog to settle before acting: a tap during the open animation was
        // measured cancelling the session ("User rejected permissions"). Matching is exact because the HUD's own
        // review text also contains the substring "Install". The semantics click is used so a dialog button that
        // is clipped by a large font scale is still actuated. Every window is searched because the dialog is not
        // always the active window, and API31 can deliver PENDING_USER_ACTION well after the request.
        fun installerButton(act: Boolean): Boolean = windows().any { root -> listOf("Install", "Update").any { label ->
            root.findAccessibilityNodeInfosByText(label).any { node -> node.isEnabled && node.text?.toString()?.equals(label, true) == true && (!act || node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) } } }
        waitUntil(75000) { installerButton(false) }
        Thread.sleep(600)
        waitUntil(30000) { installerButton(true) }
        waitUntil(90000) { workflow.store.getJob(id)?.installerStatus == InstallationStatus.INSTALLED }
        assertTrue(AppModInstaller.verifyInstalled(app, workflow, id))
        val component = ComponentName("com.mcpintelligence.fr3k.patchfixture.hudmod", "com.mcpintelligence.fr3k.patchfixture.ModLabActivity")
        app.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitUntil { visible("Patched media") && visible("Playback default: 5") && visible("https://example.org/video?keep=yes#chapter") }
        assertFalse(visible("utm_source=fixture")); assertFalse(visible("Optional panel"))
        assertTrue("Persist helper did not retain its per-context value across update", visible("normal"))
        if (updating) assertFalse(visible("changed-default"))
        val job = workflow.store.getJob(id)!!
        workflow.store.saveJob(job.copy(stage = ModificationStage.COMPLETED, verification = VerificationStatus.BEHAVIOR_VERIFIED, updatedAtMillis = System.currentTimeMillis()))
        AppModApi(app).call("request_app_mod_preparation", JSONObject().put("preview_id", id))
        clickUntil("Check installed artifact", timeout = 150000) { shown("Installed artifact matches the expected hashes and signer.", 20000) }
        assertTrue(shown("Installed artifact matches the expected hashes and signer.", 15000))
        clickUntil("I checked the changed behavior") { workflow.store.getJob(id)?.verification == VerificationStatus.USER_CONFIRMED }
        assertNotNull(workflow.store.record("install_session", id))
        click("Review removing the separate app", 60000)
        waitUntil { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings" }
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        click("Open analysis, saved jobs and offline guides", 60000)
        click("Review saved preview / resume", 60000)
        click("Digest: ${preview.getString("digest")}", 60000)
        assertTrue(visible(preview.getString("digest")))
        status("APPMOD_BEHAVIOR_PASS job=$id sha256=${job.outputSha256} sdk=${android.os.Build.VERSION.SDK_INT}")
        status("APPMOD_JOURNEY_JOB=$id")
    }

    @Test fun splitSetInstallAndArchivedOriginalRestoration() = runBlocking {
        val workflow = app.appModWorkflow
        val source = workflow.artifacts.capture(listOf(
            { app.assets.open("app-control/appmod-media-fixture.apk") },
            { app.assets.open("app-control/appmod-feature-split.apk") }), SourceKind.IMPORTED_SPLIT_SET, "Owned split acceptance fixture")
        assertEquals(2, source.files.size)
        val report = workflow.analyze(source.id)
        assertTrue(report.completed)
        val label = workflow.suggestions(report.analysisId).single { it.title == "Customize appmod_label text" }
        val preview = workflow.preview(report.analysisId, JSONArray().put(JSONObject().put("suggestionId", label.suggestionId).put("replacement", "Split media")), "same_package")
        val id = preview.getString("previewId")
        val prepared = workflow.prepare(id, workflow.approveFromUi(id, preview.getString("digest")))
        assertEquals(ModificationStage.PREPARED, prepared.stage)
        assertEquals(2, workflow.outputs(id).size)
        AppModInstaller.install(app, workflow, id)
        click("Install")
        waitUntil(90000) { workflow.store.getJob(id)?.installerStatus == InstallationStatus.INSTALLED }
        assertTrue(AppModInstaller.verifyInstalled(app, workflow, id))
        val info = app.packageManager.getApplicationInfo(source.artifact.packageName, 0)
        assertEquals(1, info.splitSourceDirs!!.size)
        val target = app.createPackageContext(source.artifact.packageName, 0)
        assertEquals("Owned split-set acceptance marker", target.assets.open("owned-split-marker.txt").bufferedReader().use { it.readText().trim() })
        val retained = workflow.outputs(id).map(ApkHashing::sha256)
        AppModInstaller.install(app, workflow, id, true)
        waitUntil(60000) { workflow.store.record("install_session", id)?.has("lastStatus") == true }
        if (workflow.store.record("install_session", id)!!.getInt("lastStatus") == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            waitUntil(60000) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root?.packageName?.toString()?.contains("packageinstaller") != true) false else
                    listOf("Install", "Update").any { label -> root.findAccessibilityNodeInfosByText(label).any { it.text?.toString() == label && it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } }
            }
        }
        waitUntil(60000) { workflow.store.getJob(id)?.installerStatus == InstallationStatus.FAILED }
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT, workflow.store.record("install_session", id)!!.getInt("lastStatus"))
        assertEquals(retained, workflow.outputs(id).map(ApkHashing::sha256))
        status("APPMOD_SIGNER_CONFLICT_PASS job=$id")
        repeat(6) {
            if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("packageinstaller") == true) {
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); Thread.sleep(200)
            }
        }
        // Test teardown removes only this owned fixture, simulating the documented signer switch.
        instrumentation.uiAutomation.executeShellCommand("pm uninstall ${source.artifact.packageName}").use { fd ->
            assertTrue(java.io.FileInputStream(fd.fileDescriptor).bufferedReader().use { it.readText() }.contains("Success"))
        }
        AppModApi(app).call("request_app_mod_preparation", JSONObject().put("preview_id", id))
        click("Review archived original restoration", 60000)
        click("Install", 60000)
        waitUntil(90000) { workflow.store.getJob(id)?.installerStatus == InstallationStatus.ORIGINAL_RESTORED }
        assertTrue(AppModInstaller.verifyInstalled(app, workflow, id))
        assertEquals(source.artifact.identity, AndroidIdentityReader.of(app, source.artifact.packageName))
        val installed = workflow.artifacts.installed(source.artifact.packageName)
        assertEquals(SourceKind.INSTALLED, installed.artifact.sourceKind)
        assertEquals(source.artifact.identity, installed.artifact.identity)
        instrumentation.uiAutomation.executeShellCommand("pm uninstall ${source.artifact.packageName}").close()
        status("APPMOD_SPLIT_AND_RESTORE_PASS job=$id sdk=${android.os.Build.VERSION.SDK_INT}")
    }

    @Test fun cancelControlCancelsOnlyItsQueuedAnalysis() = runBlocking {
        val w = app.appModWorkflow
        val source = w.artifacts.capture(listOf({ app.assets.open("app-control/appmod-gallery-fixture.apk") }), SourceKind.IMPORTED_APK, "Owned UI cancellation fixture")
        val report = w.analyze(source.id); w.suggestions(report.analysisId)
        app.getSharedPreferences("appmod.ui", 0).edit().putString("analysis", report.analysisId).putString("source", source.id).remove("preview").commit()
        app.startActivity(Intent(app, com.mcpintelligence.fr3k.ui.appcontrol.AppModDiscoveryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitUntil { visible("Choose APK") }
        // Test-only scheduling barrier: keep the UI analysis queued at the real sandbox mutex.
        // Other tests exercise cancellation while an actual Binder worker owns this same mutex.
        val gate = AppModSandbox::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(AppModSandbox) as kotlinx.coroutines.sync.Mutex
        val before = w.store.listJobs().map { it.jobId }.toSet()
        gate.lock()
        try {
            click("Resume source analysis", 60000)
            waitUntil { w.store.listJobs().any { it.jobId !in before && it.stage == ModificationStage.ANALYZING } }
            val id = w.store.listJobs().single { it.jobId !in before && it.stage == ModificationStage.ANALYZING }.jobId
            click("Cancel current work", 60000)
            waitUntil { w.store.getJob(id)?.stage == ModificationStage.CANCELLED }
            assertEquals(source.artifact.splitSha256, w.artifacts.get(source.id).files.map(ApkHashing::sha256))
            assertNull(w.store.record("outputs", id))
            status("APPMOD_UI_CANCEL_PASS job=$id")
        } finally { gate.unlock() }
    }

    @Test fun installedSelectionEvidenceSearchAndOfflineGuidesUseActualControls() = runBlocking {
        val packageName = "com.mcpintelligence.fr3k.patchfixturetwo"
        val identity = AndroidIdentityReader.of(app, packageName)
        app.startActivity(Intent(app, com.mcpintelligence.fr3k.ui.appcontrol.AppModDiscoveryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click("Allow Android installation from HUD", 60000)
        waitUntil { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings" }
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        click("Expert: import compatible Xposed module", 60000)
        waitUntil {
            var resumed = false
            instrumentation.runOnMainSync { resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).any { it is com.mcpintelligence.fr3k.ui.appcontrol.SelectedAppPatchActivity } }
            resumed
        }
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        click("Offline walkthrough and recovery", 60000)
        for (guide in appModGuides()) {
            click(guide.title, 60000)
            for ((index, step) in guide.steps.withIndex()) {
                click("${index + 1}. $step", 60000)
                assertTrue(visible(step))
            }
            status("APPMOD_GUIDE_INTERACTION_PASS id=${guide.id} steps=${guide.steps.size}")
        }
        click("Offline walkthrough and recovery", 60000)
        edit("Installed package", packageName)
        val typedBefore = app.modificationStore.analyses().map { it.analysisId }.toSet()
        click("Analyse installed app", 60000)
        waitUntil(60000) { app.modificationStore.analyses().any { it.analysisId !in typedBefore && it.artifact.identity == identity && it.artifact.sourceKind == SourceKind.INSTALLED } }
        val typedAnalysis = app.modificationStore.analyses().single { it.analysisId !in typedBefore && it.artifact.identity == identity && it.artifact.sourceKind == SourceKind.INSTALLED }
        val typedJob = app.modificationStore.getJob(typedAnalysis.analysisId)!!
        click("${typedJob.artifact!!.packageName} · ${typedJob.stage}\n${typedJob.jobId}\n${typedJob.verification} · ${typedJob.installerStatus}", 60000)
        click("Reopen analysis / resume interrupted analysis", 60000)
        click("Reopened retained analysis and verified source identity.", 60000)
        assertEquals(typedAnalysis.analysisId, app.getSharedPreferences("appmod.ui", 0).getString("analysis", null))
        click("Choose installed app", 60000)
        edit("Find installed app", packageName)
        val info = app.packageManager.getApplicationInfo(packageName, 0)
        val before = app.modificationStore.analyses().map { it.analysisId }.toSet()
        click("${app.packageManager.getApplicationLabel(info)} · $packageName", 60000)
        waitUntil(60000) { app.modificationStore.analyses().any { it.analysisId !in before && it.artifact.sourceKind == SourceKind.INSTALLED && it.artifact.identity == identity } }
        val report = app.modificationStore.analyses().first { it.analysisId !in before && it.artifact.sourceKind == SourceKind.INSTALLED }
        edit("What would you like this app to do?", "gallery caption")
        click("Find local suggestions", 60000)
        edit("Search local feature evidence", "gallery")
        click("Inspect feature map", 60000)
        val expectedEvidence = app.modificationStore.searchFeatures(report.analysisId, "gallery").first()
        click("${expectedEvidence.kind} · ${expectedEvidence.evidenceId}\n${expectedEvidence.feature}", 60000)
        assertTrue(visible(expectedEvidence.evidenceId))
        val oldConsent = app.settings.settings.value.consentProfile
        try {
            app.settings.update { it.copy(consentProfile = ConsentLevel.LOCAL_ONLY) }
            edit("Confirm public repository: owner/repository", "owned/gallery")
            click("Confirm repository and research requests", 60000)
            waitUntil { visible("Local-only consent is enabled") }
            assertNull(app.modificationStore.record("research", report.analysisId))
            click("Resume source analysis", 60000)
            waitUntil(60000) { app.getSharedPreferences("appmod.ui", 0).getString("analysis", null) != report.analysisId }
            click("Suggest with configured AI", 60000)
            waitUntil { visible("Local-only consent is enabled") }
            val current = app.getSharedPreferences("appmod.ui", 0).getString("analysis", null)!!
            assertNull(app.modificationStore.record("ai_summary", current))
        } finally { app.settings.update { it.copy(consentProfile = oldConsent) } }
        status("APPMOD_INSTALLED_SELECTION_AND_EVIDENCE_PASS analysis=${report.analysisId}")
    }

    @Test fun selectionBasketPreviewAndDraftRecoveryUseActualControls() = runBlocking {
        val w = app.appModWorkflow
        val source = w.artifacts.capture(listOf({ app.assets.open("app-control/appmod-gallery-fixture.apk") }), SourceKind.IMPORTED_APK, "Owned basket fixture")
        val report = w.analyze(source.id)
        val selected = w.suggestions(report.analysisId).single { it.title.contains("gallery_caption") }
        app.getSharedPreferences("appmod.ui", 0).edit().putString("analysis", report.analysisId).putString("source", source.id).remove("preview").commit()
        fun launch() = app.startActivity(Intent(app, com.mcpintelligence.fr3k.ui.appcontrol.AppModDiscoveryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        // The expansion control's own label is the page's state, so the state is read with a plain observation:
        // a sweeping observation scrolls the page back to its top when it gives up, which loses the list it is
        // looking at and makes the next attempt start over from the entry form.
        fun expansionState(): String? = listOf("Show top eight", "Show all suggestions (up to twenty)")
            .firstOrNull { shown(it, 300, sweep = false) }
        fun setExpanded(on: Boolean) {
            val wanted = if (on) "Show top eight" else "Show all suggestions (up to twenty)"
            clickUntil(if (on) "Show all suggestions (up to twenty)" else "Show top eight", 90000) { expansionState() == wanted }
        }
        fun filterToCaption() {
            setExpanded(true)
            edit("Search suggestions", "gallery_caption")
        }
        launch()
        filterToCaption()
        clickUntil("Select ${selected.title}") { draftSelection(report.analysisId)?.has(selected.suggestionId) == true }
        clickUntil("Select ${selected.title}") { draftSelection(report.analysisId)?.has(selected.suggestionId) == false }
        clickUntil("Select ${selected.title}") { draftSelection(report.analysisId)?.has(selected.suggestionId) == true }
        val label = "Replacement value for ${selected.title}"
        edit(label, "Basket caption")
        waitUntil { draftSelection(report.analysisId)?.optString(selected.suggestionId) == "Basket caption" }
        fun executableOnly(on: Boolean) = allNodes().any { it.contentDescription?.toString() == "Show executable suggestions only" && it.isChecked == on }
        clickUntil("Show executable suggestions only") { executableOnly(true) }
        clickUntil("Show executable suggestions only") { executableOnly(false) }
        setExpanded(false)
        for (mode in listOf("same_package", "side_by_side", "auto")) {
            clickUntil("Installation ${mode.replace('_', ' ')}") { draftMode(report.analysisId) == mode }
        }
        launch()
        filterToCaption()
        click("Basket caption", 60000)
        if (instrumentation.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        click("Preview selected changes", 60000)
        waitUntil { w.store.listJobs().any { it.analysisId == report.analysisId && it.previewId != null } }
        val job = w.store.listJobs().single { it.analysisId == report.analysisId && it.previewId != null }
        val preview = w.store.record("preview", job.jobId)!!
        val change = PatchSpec.fromJson(preview.getJSONObject("spec")).changes.single() as PatchChange.ReplaceResourceString
        assertEquals("Basket caption", change.replacement)
        assertNull(w.store.record("outputs", job.jobId))
        status("APPMOD_BASKET_DRAFT_PREVIEW_PASS analysis=${report.analysisId} preview=${job.jobId}")
    }

    @Test fun installerCancellationRetainsPreparedCopy() = runBlocking {
        val w = app.appModWorkflow
        val source = w.artifacts.capture(listOf({ app.assets.open("app-control/appmod-gallery-fixture.apk") }), SourceKind.IMPORTED_APK, "Owned installer cancellation fixture")
        val report = w.analyze(source.id)
        val selected = w.suggestions(report.analysisId).single { it.title.contains("gallery_caption") }
        val preview = w.preview(report.analysisId, JSONArray().put(JSONObject().put("suggestionId", selected.suggestionId).put("replacement", "Cancel test")))
        val id = preview.getString("previewId")
        w.prepare(id, w.approveFromUi(id, preview.getString("digest")))
        val hashes = w.outputs(id).map(ApkHashing::sha256)
        AppModApi(app).call("request_app_mod_preparation", JSONObject().put("preview_id", id))
        waitUntil { instrumentation.uiAutomation.rootInActiveWindow?.packageName == app.packageName }
        AppModInstaller.install(app, w, id)
        val session = w.store.record("install_session", id)!!.getInt("sessionId")
        status("APPMOD_INSTALL_CANCEL_WAIT job=$id session=$session")
        // API31 can deliver PENDING_USER_ACTION after more than 15 seconds under build load.
        // A failure to press the real button must fail this scenario, even if the platform aborts.
        click("Cancel", 75000)
        status("APPMOD_INSTALL_CANCEL_BUTTON session=$session")
        waitUntil(60000) { w.store.getJob(id)?.installerStatus == InstallationStatus.CANCELLED }
        assertEquals(ModificationStage.PREPARED, w.store.getJob(id)!!.stage)
        assertEquals(hashes, w.outputs(id).map(ApkHashing::sha256))
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED, w.store.record("install_session", id)!!.getInt("lastStatus"))
        waitUntil(30000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == app.packageName }
        AppModInstaller.install(app, w, id)
        waitUntil(30000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("packageinstaller") == true }
        Thread.sleep(600)
        click("Cancel", 75000)
        waitUntil(60000) { w.store.getJob(id)?.installerStatus == InstallationStatus.CANCELLED }
        assertEquals(hashes, w.outputs(id).map(ApkHashing::sha256))
        status("APPMOD_INSTALL_CANCEL_PASS sdk=${android.os.Build.VERSION.SDK_INT} explicitButton=true retry=true")
    }

    @Test fun publicResearchUsesLiveGithubTransport() = runBlocking {
        val workflow = app.appModWorkflow
        val source = workflow.artifacts.capture(listOf({ app.assets.open("app-control/appmod-gallery-fixture.apk") }), SourceKind.IMPORTED_APK, "Owned live research fixture")
        val report = workflow.analyze(source.id)
        AppModResearch.confirmRepository(workflow.store, report, "FossifyOrg/Gallery")
        val requests = AppModResearch.refresh(workflow.store, report)
        assertTrue("Expected public enhancement requests", requests.length() > 0)
        for (i in 0 until requests.length()) assertTrue(requests.getJSONObject(i).getString("url").startsWith("https://github.com/FossifyOrg/Gallery/issues/"))
        status("APPMOD_LIVE_GITHUB_PASS requests=${requests.length()} analysis=${report.analysisId}")
    }

    @Test fun documentPickerImportsWithoutStoragePermission() = runBlocking {
        // Harness initializes only the owned fixture in shared Download storage before this test.
        // The product then receives its real scoped DocumentsUI URI, with no storage permission.
        val importedPackage = "com.mcpintelligence.fr3k.patchfixturetwo"
        fun dismissPicker() {
            repeat(6) {
                if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true) {
                    instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    Thread.sleep(200)
                }
            }
        }
        fun imported(before: Set<String>) = app.modificationStore.analyses()
            .any { it.analysisId !in before && it.artifact.packageName == importedPackage }
        try {
            // A picker left open by an earlier test owns the active window and would swallow the navigation.
            dismissPicker()
            val before = app.modificationStore.analyses().map { it.analysisId }.toSet()
            // DocumentsUI keeps its own location history and a folder row can report a click that does not
            // navigate, so every step is re-checked against the real file listing. The pass criterion below is
            // still the real scoped URI reaching the product store.
            fun fileListed() = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText("owned-appmod-selection.apk")?.isNotEmpty() == true
            fun navigateOnce(): Boolean {
                app.startActivity(Intent(app, com.mcpintelligence.fr3k.ui.appcontrol.AppModDiscoveryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                click("Choose APK or base + split APKs")
                if (!fileListed()) runCatching { click("Show roots", 6000) }
                if (!fileListed()) runCatching { click(BUILD_MODEL, 6000) }
                repeat(3) {
                    if (fileListed()) return runCatching { click("owned-appmod-selection.apk", 8000); true }.getOrDefault(false)
                    runCatching { click("Download", 8000) }
                    Thread.sleep(500)
                }
                return runCatching { click("owned-appmod-selection.apk", 8000); true }.getOrDefault(false)
            }
            var selected = false
            for (attempt in 0 until 3) {
                if (attempt > 0) dismissPicker()
                if (navigateOnce()) { selected = true; break }
                if (imported(before)) { selected = true; break }
            }
            assertTrue("DocumentsUI never listed the owned fixture for selection", selected || imported(before))
            waitUntil(60000) { imported(before) }
            status("APPMOD_DOCUMENT_PICKER_PASS sdk=${android.os.Build.VERSION.SDK_INT}")
        } finally {
            dismissPicker()
        }
    }

    @Test fun aiBoundaryAndAllFiveTools() = runBlocking {
        val workflow = app.appModWorkflow
        val source = workflow.artifacts.capture(listOf({ app.assets.open("app-control/appmod-gallery-fixture.apk") }), SourceKind.IMPORTED_APK, "Owned AI boundary fixture")
        val response = AppModApi(app).call("analyze_apk", JSONObject().put("artifact_id", source.id))
        val id = response.getString("analysisId"); val report = workflow.store.getAnalysis(id)!!
        assertTrue(AppModApi(app).call("suggest_app_mods", JSONObject().put("analysis_id", id)).getJSONArray("suggestions").length() > 0)
        assertEquals(id, AppModApi(app).call("get_app_mod_job", JSONObject().put("job_id", id)).getString("jobId"))
        val targetIndex = report.evidence.indexOfFirst { it.reference is EvidenceReference.Resource && JSONObject(it.feature).optString("name") == "gallery_caption" }
        assertTrue(targetIndex in 0..119)
        val fake = object : AiProvider {
            override val id = "fixture"; override val displayName = "fixture"
            override val capabilities = emptySet<String>(); override val requiresNetwork = false; override val requiresApiKey = false
            override suspend fun health() = ProviderHealth(true)
            override suspend fun ask(request: AgentAskRequest): AgentAskResponse {
                assertTrue(request.attachments.isEmpty()); assertFalse(request.prompt.contains("super-secret-value")); assertTrue(request.prompt.toByteArray().size < 40000)
                return AgentAskResponse(JSONObject().put("ideas", JSONArray().put(JSONObject().put("title", "Improve gallery caption")
                    .put("benefit", "Use a clearer local caption").put("evidence_ids", JSONArray().put("e$targetIndex"))
                    .put("request_ids", JSONArray()).put("replacement", "Photo memories"))).toString())
            }
        }
        val ideas = AppModResearch.aiIdeas(workflow.store, report, fake, "PASSWORD=super-secret-value")
        val suggestion = ModificationSuggestion.fromJson(ideas.getJSONObject(0))
        assertEquals(Readiness.CAN_GENERATE, suggestion.readiness)
        workflow.store.saveSuggestion(suggestion)
        val preview = AppModApi(app).call("preview_app_mods", JSONObject().put("analysis_id", id).put("selections", JSONArray().put(JSONObject()
            .put("suggestionId", suggestion.suggestionId).put("replacement", suggestion.suggestedReplacement))))
        assertTrue(preview.has("digest")); assertFalse(preview.toString().contains("super-secret-value"))
        try { AppModApi(app).call("request_app_mod_preparation", JSONObject().put("preview_id", preview.getString("previewId")).put("approve", true)); fail("Agent approval accepted") }
        catch (expected: IllegalArgumentException) {}
        assertNull(workflow.store.record("outputs", preview.getString("previewId")))
        status("APPMOD_AI_BOUNDARY_AND_TOOLS_PASS")
    }
}
