package com.mcpintelligence.fr3k.appcontrol

import android.content.Intent
import android.os.*
import android.view.FrameMetrics
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.ui.appcontrol.AppModDiscoveryActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.UUID

/** Emulator measurements with an explicit budget; physical performance remains a separate gate. */
class AppModPerformanceDeviceTest {
    @Test fun startupAndScrollWithRealisticHistoryAvoidMainThreadDiskIo() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext.applicationContext as Fr3kApplication
        val store = app.modificationStore
        val sample = store.listJobs().first { it.outputSha256 != null && it.artifact != null }
        val prefix = "perf-${UUID.randomUUID()}-"
        store.transaction { repeat(100) { i -> store.saveJob(sample.copy(jobId = prefix + i, createdAtMillis = System.currentTimeMillis() + i, updatedAtMillis = System.currentTimeMillis() + i)) } }
        val frames = Collections.synchronizedList(mutableListOf<Long>())
        val disk = Collections.synchronizedList(mutableListOf<String>())
        var oldPolicy: StrictMode.ThreadPolicy? = null
        inst.runOnMainSync {
            oldPolicy = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(oldPolicy!!).detectDiskReads().detectDiskWrites().penaltyListener({ it.run() }) { violation ->
                if (violation.stackTrace.any { it.className.startsWith("com.mcpintelligence.fr3k.appcontrol") || it.className.startsWith("com.mcpintelligence.fr3k.ui.appcontrol") }) disk += violation.stackTraceToString()
            }.build())
        }
        val started = SystemClock.elapsedRealtime()
        try {
            ActivityScenario.launch<AppModDiscoveryActivity>(Intent(app, AppModDiscoveryActivity::class.java)).use { scenario ->
                scenario.onActivity { activity -> activity.window.addOnFrameMetricsAvailableListener({ _: Window, metrics: FrameMetrics, _: Int -> frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION) }, Handler(Looper.getMainLooper())) }
                inst.waitForIdleSync()
                val readyDeadline = SystemClock.elapsedRealtime() + 20000
                fun hasScroll(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean = node != null && (node.isScrollable || (0 until node.childCount).any { hasScroll(node.getChild(it)) })
                while (!hasScroll(inst.uiAutomation.rootInActiveWindow) && SystemClock.elapsedRealtime() < readyDeadline) Thread.sleep(50)
                assertTrue("UI never exposed its scroll container", hasScroll(inst.uiAutomation.rootInActiveWindow))
                val startup = SystemClock.elapsedRealtime() - started
                var moved = 0
                var forward = true
                repeat(20) {
                    val root = inst.uiAutomation.rootInActiveWindow
                    fun scroll(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                        if (node == null) return false
                        if (node.isScrollable && node.performAction(if (forward) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
                        return (0 until node.childCount).any { scroll(node.getChild(it)) }
                    }
                    if (scroll(root)) moved++ else forward = !forward
                    Thread.sleep(150)
                }
                inst.waitForIdleSync()
                val observed = synchronized(frames) { frames.sorted() }
                assertTrue("Fewer than five actual scroll actions", moved >= 5)
                assertTrue("No frame measurements", observed.isNotEmpty())
                val p95 = observed[((observed.size - 1) * .95).toInt()] / 1_000_000.0
                inst.sendStatus(0, Bundle().apply { putString("stream", "APPMOD_PERFORMANCE_MEASURED warmStartupMs=$startup frames=${observed.size} p95Ms=$p95 history=100 appDiskViolations=${disk.size}\n") })
                assertTrue("Warm startup exceeded 10s emulator budget", startup < 10000)
                assertTrue("Scrolling p95 exceeded 100ms emulator budget: $p95", p95 < 100)
                assertTrue("App main-thread disk I/O: $disk", disk.isEmpty())
            }
        } finally {
            inst.runOnMainSync { oldPolicy?.let(StrictMode::setThreadPolicy) }
            store.writableDatabase.delete("job", "job_id LIKE ?", arrayOf("$prefix%"))
        }
    }
}
