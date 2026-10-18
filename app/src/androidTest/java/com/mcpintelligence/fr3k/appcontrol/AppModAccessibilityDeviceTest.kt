package com.mcpintelligence.fr3k.appcontrol

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import org.junit.Assert.*
import org.junit.Test

/** Automated label/reachability checks. Does not claim manual TalkBack or physical frame timings. */
class AppModAccessibilityDeviceTest {
    @Test fun discoveryControlsAreLabelledAcrossScrollablePagesAtCurrentScale() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext.applicationContext as Fr3kApplication
        inst.uiAutomation.serviceInfo = inst.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        app.startActivity(Intent(app, com.mcpintelligence.fr3k.ui.appcontrol.AppModDiscoveryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        // The launched activity must own the active window before any node reads; otherwise rootInActiveWindow is null.
        fun hudRoot(): AccessibilityNodeInfo {
            val deadline = android.os.SystemClock.elapsedRealtime() + 20000
            var last: AccessibilityNodeInfo? = null
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                last = inst.uiAutomation.rootInActiveWindow
                if (last != null && last.packageName?.toString() == app.packageName) return last
                Thread.sleep(150)
            }
            assertNotNull("HUD discovery window never became active (last=${last?.packageName})", last)
            return last!!
        }
        hudRoot()
        fun label(n: AccessibilityNodeInfo, depth: Int = 0): String {
            // A text field's visible label/placeholder is exposed as hintText (Android views and Compose fields).
            val own = n.text?.toString().orEmpty()
                .ifBlank { n.contentDescription?.toString().orEmpty() }
                .ifBlank { n.hintText?.toString().orEmpty() }
            if (own.isNotBlank() || depth >= 8) return own
            return (0 until n.childCount).mapNotNull { n.getChild(it) }.map { label(it, depth + 1) }.firstOrNull { it.isNotBlank() }.orEmpty()
        }
        fun nodes(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) + (0 until n.childCount).mapNotNull { n.getChild(it) }.flatMap { nodes(it) }
        // Visible label set: identical after a swipe means the container did not move (Compose reports
        // ACTION_SCROLL_FORWARD success at the list end). Labels ignore animation-only repaints, unlike bounds.
        fun visibleLabels(root: AccessibilityNodeInfo): Int =
            nodes(root).filter { it.isVisibleToUser }
                .mapNotNull { n -> n.text?.toString()?.takeIf(String::isNotBlank)
                    ?: n.contentDescription?.toString()?.takeIf(String::isNotBlank)
                    ?: n.hintText?.toString()?.takeIf(String::isNotBlank) }
                .distinct().sorted().joinToString("\n").hashCode()
        fun swipeUp(root: AccessibilityNodeInfo) {
            val window = Rect(); root.getBoundsInScreen(window)
            val x = window.exactCenterX()
            val from = window.top + window.height() * 0.8f
            val to = window.top + window.height() * 0.2f
            val start = android.os.SystemClock.uptimeMillis()
            fun event(action: Int, y: Float, at: Long): android.view.MotionEvent =
                android.view.MotionEvent.obtain(start, at, action, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            val events = ArrayList<android.view.MotionEvent>()
            events += event(android.view.MotionEvent.ACTION_DOWN, from, start)
            repeat(7) { step -> events += event(android.view.MotionEvent.ACTION_MOVE, from + (to - from) * (step + 1) / 8f, start + 20L * (step + 1)) }
            events += event(android.view.MotionEvent.ACTION_UP, to, start + 200L)
            try { events.forEach { inst.uiAutomation.injectInputEvent(it, true) } }
            finally { events.forEach { it.recycle() } }
        }
        val names = linkedSetOf<String>()
        var ended = false
        var pages = 0
        var stillPages = 0
        var lastFingerprint = 0
        for (page in 0 until 120) {
            val root = hudRoot()
            val window = Rect(); root.getBoundsInScreen(window)
            val all = nodes(root)
            for (n in all.filter { it.isClickable && it.isEnabled && it.isVisibleToUser }) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty && window.contains(r) && r.height() >= (48 * app.resources.displayMetrics.density).toInt()) {
                    var text = label(n)
                    var stillPresent = true
                    // A node can be invalidated while the 750ms job refresh recomposes it.
                    // Recheck the live control before declaring a stable missing label.
                    repeat(3) {
                        if (text.isBlank() && stillPresent) {
                            Thread.sleep(100)
                            val current = nodes(hudRoot()).firstOrNull { candidate ->
                                val bounds = Rect(); candidate.getBoundsInScreen(bounds)
                                candidate.isClickable && candidate.isEnabled && candidate.isVisibleToUser && bounds == r
                            }
                            stillPresent = current != null
                            text = current?.let { label(it) }.orEmpty()
                        }
                    }
                    assertTrue("Unlabelled stable control ${n.className} $r", !stillPresent || text.isNotBlank())
                    if (text.isNotBlank()) names += text
                }
            }
            pages++
            val current = visibleLabels(root)
            stillPages = if (current == lastFingerprint) stillPages + 1 else 0
            lastFingerprint = current
            if (all.none { it.isScrollable }) { ended = true; break }
            swipeUp(root)
            Thread.sleep(600)
            if (stillPages >= 4) { ended = true; break }
        }
        assertTrue("Scroll end not reached in bounded audit pages=$pages", ended)
        inst.sendStatus(0, android.os.Bundle().apply { putString("stream", "APPMOD_ACCESSIBILITY_LABELS names=$pages/${names.size} :: " + names.joinToString(" | ").take(1800) + "\n") })
        assertTrue("Choose APK control missing: ${names.joinToString(" | ").take(600)}", names.any { it.contains("Choose APK") })
        assertTrue("Offline walkthrough control missing: ${names.joinToString(" | ").take(600)}", names.any { it.contains("Offline walkthrough") })
        inst.sendStatus(0, android.os.Bundle().apply { putString("stream", "APPMOD_ACCESSIBILITY_LABELS_PASS pages=$pages controls=${names.size} fontScale=${app.resources.configuration.fontScale} widthDp=${app.resources.configuration.screenWidthDp}\n") })
    }
}
