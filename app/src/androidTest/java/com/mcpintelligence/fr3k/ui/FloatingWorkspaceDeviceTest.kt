package com.mcpintelligence.fr3k.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.hud.overlays.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingWorkspaceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Fr3kApplication
    private fun find(view: View, label: String): View? {
        if (view.contentDescription?.toString() == label) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), label)?.let { return it }
        return null
    }
    private fun drag(view: View, dx: Float, dy: Float) {
        val t = SystemClock.uptimeMillis()
        listOf(Triple(MotionEvent.ACTION_DOWN, 20f, 20f), Triple(MotionEvent.ACTION_MOVE, 20f + dx, 20f + dy), Triple(MotionEvent.ACTION_UP, 20f + dx, 20f + dy)).forEach { (action, x, y) ->
            MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
        }
    }
    @Test fun allWorkspaceWindowsMoveResizeAndPassOutsideTaps() {
        app.settings.update { it.copy(onboardingDone = true) }
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var clicks = 0
        lateinit var button: Button
        instrumentation.runOnMainSync {
            activity.stopService(Intent(app, com.mcpintelligence.fr3k.hud.HudOverlayService::class.java))
            val frame = FrameLayout(activity)
            button = Button(activity).apply { text = "Underlying app action"; setOnClickListener { clicks++ } }
            frame.addView(button, FrameLayout.LayoutParams(-1, 150, Gravity.BOTTOM).apply { bottomMargin = 160 })
            activity.setContentView(frame)
        }
        instrumentation.waitForIdleSync()
        val host = OverlayHost(app, app.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        val windows = ArrayList<Pair<Fr3kOverlay, View>>()
        instrumentation.runOnMainSync {
            Fr3kChatBubble(host).also { windows.add(it to it.rootView()) }
            Fr3kMiniBrowserOverlay(host).also { windows.add(it to it.rootView()) }
            Fr3kTerminalOverlay(host).also { windows.add(it to it.rootView()) }
            RadialMenuOverlay(host).also { windows.add(it to it.rootView()) }
        }
        try {
            windows.forEach { (overlay, root) ->
                instrumentation.runOnMainSync { overlay.show() }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertTrue("${overlay.name} attached", overlay.isAttached)
                    val params = root.layoutParams as WindowManager.LayoutParams
                    assertTrue("outside touch flag ${overlay.name}", params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
                    params.x = 30; params.y = 150; params.width = 800; params.height = 800
                    host.update(root, params)
                    val move = find(root, "Move floating window") ?: error("missing move handle: ${overlay.name}")
                    drag(move, 45f, 55f)
                    assertEquals("move x ${overlay.name}", 75, params.x)
                    assertEquals("move y ${overlay.name}", 205, params.y)
                    val grip = find(root, "Resize floating window") ?: error("missing resize: ${overlay.name}")
                    drag(grip, 80f, 70f)
                    assertEquals("resize width ${overlay.name}", 880, params.width)
                    assertEquals("resize height ${overlay.name}", 870, params.height)
                }
                instrumentation.waitForIdleSync()
                val location = IntArray(2)
                instrumentation.runOnMainSync { button.getLocationOnScreen(location) }
                val x = location[0] + button.width / 2f; val y = location[1] + button.height / 2f
                val before = clicks
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
                }
                instrumentation.waitForIdleSync()
                assertEquals("outside tap reaches underlying app with ${overlay.name} open", before + 1, clicks)
                instrumentation.runOnMainSync { overlay.hide() }
            }
        } finally {
            instrumentation.runOnMainSync {
                windows.forEach { (overlay, _) -> when (overlay) {
                    is Fr3kChatBubble -> overlay.shutdown()
                    is Fr3kMiniBrowserOverlay -> overlay.shutdown()
                    is Fr3kTerminalOverlay -> overlay.shutdown()
                    else -> overlay.hide()
                } }
                activity.finish()
            }
        }
    }
}
