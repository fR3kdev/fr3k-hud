package com.mcpintelligence.fr3k.bridge

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.mcpintelligence.fr3k.capability.UiNode

/**
 * The bridge plane's only Android surface. It can read the accessibility tree, tap, edit text and
 * draw an accessibility overlay. It cannot change another app's code or read credential fields.
 */
class Fr3kBridgeService : AccessibilityService() {

    companion object {
        @Volatile
        private var live: Fr3kBridgeService? = null

        fun connected(): Boolean = live != null
        fun instanceOrNull(): Fr3kBridgeService? = live
        fun require(): Fr3kBridgeService = live ?: error("accessibility service is not connected")

        const val ACTION_SET_STATUS = "com.mcpintelligence.fr3k.hud.SET_TARGET_TWO_STATUS"
        const val ACTION_SET_OWNED_FIXTURE_BANNER = "com.mcpintelligence.fr3k.hud.SET_OWNED_FIXTURE_BANNER"
    }

    private var windowManager: WindowManager? = null
    private val masks = ArrayList<View>()

    val maskCount: Int get() = masks.size

    /** Bounds of the most recent mask, so a test can verify the covered pixels actually changed. */
    @Volatile var lastMaskBounds: Rect? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        live = this
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
    }

    override fun onDestroy() {
        clearMasks()
        live = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * Window and node work must happen on the accessibility service's own thread: adding an overlay
     * from a worker thread throws "Can't create handler inside thread that has not called
     * Looper.prepare()". Every entry point marshals here so callers can run anywhere.
     */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = java.util.concurrent.CountDownLatch(1)
        var outcome: Result<T>? = null
        Handler(Looper.getMainLooper()).post {
            outcome = runCatching(block)
            latch.countDown()
        }
        if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
            throw IllegalStateException("accessibility service did not answer within 15s")
        }
        return (outcome ?: throw IllegalStateException("no result")).getOrThrow()
    }

    fun readTree(): UiNode? = onMain {
        val root = rootInActiveWindow ?: return@onMain null
        try {
            UiTreeReader.toUiNode(root)
        } catch (error: IllegalStateException) {
            null
        }
    }

    fun foregroundPackage(): String? = onMain { rootInActiveWindow?.packageName?.toString() }

    /** The live node, needed to act on a matched path (the UiNode model is an immutable copy). */
    fun liveRoot(): android.view.accessibility.AccessibilityNodeInfo? = onMain { rootInActiveWindow }

    fun launch(intent: android.content.Intent) = onMain { startActivity(intent) }

    fun back() = onMain {
        performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun showMask(bounds: Rect) = onMain {
        val manager = windowManager ?: windowManagerOrNull() ?: return@onMain
        val view = View(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val params = WindowManager.LayoutParams(
            bounds.width().coerceAtLeast(1),
            bounds.height().coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }
        manager.addView(view, params)
        masks.add(view)
        lastMaskBounds = Rect(bounds)
    }

    private fun windowManagerOrNull(): WindowManager? =
        (getSystemService(WINDOW_SERVICE) as? WindowManager)?.also { windowManager = it }

    fun clearMasks() = onMain {
        val manager = windowManager ?: windowManagerOrNull() ?: return@onMain
        masks.forEach { view -> runCatching { manager.removeView(view) } }
        masks.clear()
    }
}
