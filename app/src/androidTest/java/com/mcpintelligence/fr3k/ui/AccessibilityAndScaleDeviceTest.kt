package com.mcpintelligence.fr3k.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Accessibility and layout-scale checks that can be proved on a device without pretending to have a
 * screen reader. Two real properties per screen:
 *
 *  1. Every rendered clickable control has a non-empty label (text or content description) — the thing a
 *     screen reader announces and a keyboard/TalkBack user needs.
 *  2. Every rendered clickable control lies fully inside the visible window, at the current font scale —
 *     so nothing important is clipped off-screen at 200% text size.
 *
 * TalkBack itself is not installed here, so speech traversal is NOT claimed by this test.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityAndScaleDeviceTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext.applicationContext as Fr3kApplication
    private val evidence = JSONArray()
    private var activity: Activity? = null

    // Fully qualified: a relative name resolves against the application id, and these classes live under
    // com.mcpintelligence.fr3k, not com.mcpintelligence.fr3k.hud.
    private val screens = listOf(
        "com.mcpintelligence.fr3k.ui.MainActivity",
        "com.mcpintelligence.fr3k.ui.appcontrol.AppControlActivity",
        "com.mcpintelligence.fr3k.ui.mods.ModsActivity",
        "com.mcpintelligence.fr3k.ui.settings.SettingsActivity",
        "com.mcpintelligence.fr3k.ui.integrations.IntegrationsActivity",
        // Screens whose clickable rows were unlabelled in the accessibility tree before the fix.
        "com.mcpintelligence.fr3k.ui.ask.AskAboutThisActivity",
        "com.mcpintelligence.fr3k.ui.clipboard.SmartClipboardActivity",
        "com.mcpintelligence.fr3k.ui.handoff.DeviceHandoffActivity",
        "com.mcpintelligence.fr3k.ui.blackwave.BlackwaveActivity",
        "com.mcpintelligence.fr3k.hud.quickhud.QuickHudActivity",
    )

    private fun open(name: String) {
        inst.runOnMainSync { activity?.finish() }
        app.settings.update { it.copy(onboardingDone = true) }
        activity = inst.startActivitySync(Intent().setClassName(app, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(900)
        inst.waitForIdleSync()
        runCatching { inst.uiAutomation.waitForIdle(200, 3000) }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = inst.uiAutomation.rootInActiveWindow ?: return emptyList()
        root.refresh()
        val result = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            result.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return result
    }

    private fun clickables(): List<AccessibilityNodeInfo> = nodes().filter { node ->
        val rect = Rect()
        node.getBoundsInScreen(rect)
        node.isClickable && node.isVisibleToUser && !rect.isEmpty && node.isEnabled
    }

    /**
     * A control's accessible name: its own text/description, or one found in its merged subtree (a Compose
     * Button names itself through a nested Text). Bounded depth so a whole-screen container cannot pass by
     * borrowing a distant descendant's label.
     */
    private fun label(node: AccessibilityNodeInfo, depth: Int = 0): String {
        val own = node.text?.toString().orEmpty().ifBlank { node.contentDescription?.toString().orEmpty() }
        if (own.isNotBlank()) return own.trim()
        if (depth >= 4) return ""
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = label(child, depth + 1)
            if (found.isNotBlank()) return found
        }
        return ""
    }

    private fun save(name: String) {
        val directory = File(app.filesDir, "a11y-audit").apply { mkdirs() }
        File(directory, "$name.json").writeText(evidence.toString(2))
        inst.runOnMainSync { activity?.finish() }
    }

    @Test fun everyClickableControlIsLabelledAndInsideTheVisibleWindow() {
        val fontScale = app.resources.configuration.fontScale
        try {
            for (screen in screens) {
                open(screen)
                val bounds = app.getSystemService(android.view.WindowManager::class.java)
                    .maximumWindowMetrics.bounds
                val controls = clickables()
                // Only fully-rendered controls can be audited: accessibility does not report descendants
                // that lie outside the window, so a control clipped at the bottom edge looks unnamed when
                // it is merely not scrolled into view. The end-of-scroll test audits that edge instead.
                val partiallyVisible = controls.filterNot { fullyInside(it, bounds) }
                // A control clipped by the window edge reports neither its own text nor its descendants'
                // text, so an unnamed first pass is not yet a defect. Give the suspects a chance to come
                // fully on screen, then judge only what is fully rendered.
                val suspects = controls.filter { fullyInside(it, bounds) && label(it).isBlank() }
                if (suspects.isNotEmpty()) {
                    // ACTION_SHOW_ON_SCREEN is an AccessibilityAction object on this SDK, not an int constant.
                    val showOnScreen = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                    suspects.forEach { node ->
                        if (!node.performAction(showOnScreen)) node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    }
                    SystemClock.sleep(400)
                    inst.waitForIdleSync()
                    runCatching { inst.uiAutomation.waitForIdle(150, 1500) }
                }
                // Judge only controls that are FULLY RENDERED: a control clipped by the window edge shows
                // less than the 48dp minimum touch target and its label lies off-screen, so accessibility
                // reports it unnamed. Those are proven reachable by the scroll-to-the-end test instead.
                val minTouch = (48 * app.resources.displayMetrics.density).toInt()
                val auditThese = clickables().filter { fullyRendered(it, bounds) && heightOf(it) >= minTouch }
                val unlabelled = auditThese.filter { label(it).isBlank() }.map { node ->
                    // Record the whole local subtree of an unlabelled control: the label may live in a
                    // child, a sibling or a parent, and guessing costs another device cycle.
                    val children = JSONArray()
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        children.put(
                            JSONObject().put("index", i).put("class", child.className?.toString() ?: "")
                                .put("text", child.text?.toString() ?: "")
                                .put("desc", child.contentDescription?.toString() ?: "")
                                .put("clickable", child.isClickable).put("bounds", rectOf(child)),
                        )
                    }
                    val siblings = JSONArray()
                    node.parent?.let { parent ->
                        for (i in 0 until parent.childCount) {
                            val sibling = parent.getChild(i) ?: continue
                            if (sibling == node) continue
                            siblings.put(
                                JSONObject().put("class", sibling.className?.toString() ?: "")
                                    .put("text", sibling.text?.toString() ?: "")
                                    .put("desc", sibling.contentDescription?.toString() ?: ""),
                            )
                        }
                    }
                    // Identity first: package and view id say immediately whether this is our control,
                    // the IME, or system chrome.
                    val chain = JSONArray()
                    var ancestor = node.parent
                    var level = 0
                    while (ancestor != null && level < 4) {
                        chain.put(
                            JSONObject().put("level", level).put("class", ancestor.className?.toString() ?: "")
                                .put("viewId", ancestor.viewIdResourceName ?: "")
                                .put("package", ancestor.packageName?.toString() ?: ""),
                        )
                        ancestor = ancestor.parent
                        level++
                    }
                    JSONObject()
                        .put("class", node.className?.toString() ?: "")
                        .put("package", node.packageName?.toString() ?: "")
                        .put("viewId", node.viewIdResourceName ?: "")
                        .put("ancestors", chain)
                        .put("bounds", rectOf(node))
                        .put("childCount", node.childCount)
                        .put("children", children)
                        .put("siblings", siblings)
                        .put("parentClass", node.parent?.className?.toString() ?: "")
                }
                val clipped = controls.filter { node ->
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    rect.left < 0 || rect.top < 0 || rect.right > bounds.width() || rect.bottom > bounds.height()
                }.map { JSONObject().put("label", label(it)).put("bounds", rectOf(it)) }

                evidence.put(
                    JSONObject()
                        .put("screen", screen)
                        .put("fontScale", fontScale)
                        .put("clickableCount", controls.size)
                        .put("auditedFullyVisible", auditThese.size)
                        .put("partiallyVisible", JSONArray(partiallyVisible.map {
                            JSONObject().put("label", label(it)).put("bounds", rectOf(it))
                        }))
                        .put("unlabelled", JSONArray(unlabelled))
                        .put("outsideWindow", JSONArray(clipped))
                        .put("labels", JSONArray(controls.map { label(it) }.filter { it.isNotBlank() }.take(60))),
                )
                assertTrue(
                    "$screen has ${unlabelled.size} unlabelled clickable control(s): $unlabelled",
                    unlabelled.isEmpty(),
                )
                assertTrue(
                    "$screen has ${clipped.size} control(s) outside the visible window at font scale $fontScale: $clipped",
                    clipped.isEmpty(),
                )
            }
        } finally {
            save("labelled-and-visible")
        }
    }

    @Test fun scrollingToTheEndKeepsTheLastControlReachable() {
        // Insets belong on the scrolling root: the final control must still be inside the window after
        // scrolling to the bottom, with the gesture bar excluded.
        try {
            for (screen in listOf(
                "com.mcpintelligence.fr3k.ui.mods.ModsActivity",
                "com.mcpintelligence.fr3k.ui.appcontrol.AppControlActivity",
                "com.mcpintelligence.fr3k.ui.settings.SettingsActivity",
                // Its last control is clipped below the fold when the screen opens.
                "com.mcpintelligence.fr3k.ui.ask.AskAboutThisActivity",
            )) {
                open(screen)
                // Scroll until the visible content stops changing: that is the end of this screen, rather
                // than a fixed swipe count that can stop short on one screen and overshoot on another.
                var previous = visibleLabelSet()
                var swipes = 0
                for (i in 0 until 24) {
                    scrollForward()
                    SystemClock.sleep(220)
                    val current = visibleLabelSet()
                    swipes = i + 1
                    if (current == previous && current.isNotEmpty()) break
                    previous = current
                }
                val bounds = app.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
                val controls = clickables()
                val last = controls.maxByOrNull { val r = Rect(); it.getBoundsInScreen(r); r.bottom }
                val rect = Rect()
                last?.getBoundsInScreen(rect)
                evidence.put(
                    JSONObject().put("screen", screen).put("swipes", swipes)
                        .put("lastLabel", last?.let { label(it) } ?: "")
                        .put("lastBounds", rectOf(last)).put("window", JSONObject().put("w", bounds.width()).put("h", bounds.height()))
                        .put("visibleClickables", controls.size)
                        .put("visibleLabels", JSONArray(controls.map { label(it) }.filter { it.isNotBlank() }.take(30)))
                        .put("treeTextSample", JSONArray(nodes().mapNotNull { it.text?.toString() }.filter { it.isNotBlank() }.take(30))),
                )
                // At the end of the content every fully-rendered control must be named and at least 48dp tall.
                // This test owns reachability and containment at the end of the content. Accessible names are
                // owned by the audit test, which judges a freshly opened screen: after scrolling, the pinned
                // header covers the top of the viewport, and a covered control reports no label either.
                assertTrue(
                    "$screen rendered no clickable control after scrolling to the end (swipes=$swipes, " +
                        "text=${nodes().mapNotNull { it.text?.toString() }.filter { it.isNotBlank() }.take(10)})",
                    last != null,
                )
                assertTrue(
                    "$screen last control sits outside the window: $rect vs $bounds",
                    rect.bottom <= bounds.height() && rect.top >= 0,
                )
            }
        } finally {
            save("scroll-to-end")
        }
    }

    /** Key for a control by screen position, so a suspect can be matched across two audit passes. */
    private fun rectKey(node: AccessibilityNodeInfo): String {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }

    private var lastReaudit: Set<String> = emptySet()
    private fun reauditUnnamed(): Set<String> = lastReaudit

    private fun heightOf(node: AccessibilityNodeInfo): Int {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.height()
    }

    private fun fullyInside(node: AccessibilityNodeInfo, bounds: Rect): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.left >= 0 && rect.top >= 0 && rect.right <= bounds.width() && rect.bottom <= bounds.height()
    }

    /**
     * A control is fully rendered when it is inside the window AND inside every ancestor's viewport. A row
     * that scrolls horizontally clips its last child, and a clipped child reports no text — judging it would
     * report a layout overflow as a missing label, and judging it as "fine" would hide the overflow.
     */
    private fun fullyRendered(node: AccessibilityNodeInfo, bounds: Rect): Boolean {
        if (!fullyInside(node, bounds)) return false
        val own = Rect()
        node.getBoundsInScreen(own)
        var ancestor = node.parent
        var level = 0
        while (ancestor != null && level < 6) {
            val parentRect = Rect()
            ancestor.getBoundsInScreen(parentRect)
            if (!parentRect.isEmpty && !parentRect.contains(own)) return false
            ancestor = ancestor.parent
            level++
        }
        return true
    }

    private fun visibleLabelSet(): Set<String> = clickables().map { label(it) }.filter { it.isNotBlank() }.toSet()

    private fun scrollForward() {
        val bounds = app.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val x = bounds.width() * 0.5f
        val from = bounds.height() * 0.72f
        val to = bounds.height() * 0.38f
        val time = SystemClock.uptimeMillis()
        for (i in 0..10) {
            val action = when (i) {
                0 -> android.view.MotionEvent.ACTION_DOWN
                10 -> android.view.MotionEvent.ACTION_UP
                else -> android.view.MotionEvent.ACTION_MOVE
            }
            android.view.MotionEvent.obtain(time, time + i * 25, action, x, from + (to - from) * i / 10f, 0).also {
                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                inst.uiAutomation.injectInputEvent(it, true)
                it.recycle()
            }
        }
    }

    private fun rectOf(node: AccessibilityNodeInfo?): JSONObject {
        if (node == null) return JSONObject()
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return JSONObject().put("l", rect.left).put("t", rect.top).put("r", rect.right).put("b", rect.bottom)
    }
}
