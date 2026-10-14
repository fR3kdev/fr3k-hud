package com.mcpintelligence.fr3k.ui

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcpintelligence.fr3k.Fr3kApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingSurfaceDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext.applicationContext as Fr3kApplication
    private fun find(view: View, label: String): View? {
        if (view.contentDescription?.toString() == label || (view is TextView && view.text.toString() == label)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), label)?.let { return it }
        return null
    }
    private fun drag(view: View, dx: Float, dy: Float) {
        val start = SystemClock.uptimeMillis()
        listOf(Triple(MotionEvent.ACTION_DOWN, 10f, 10f), Triple(MotionEvent.ACTION_MOVE, 10f + dx, 10f + dy), Triple(MotionEvent.ACTION_UP, 10f + dx, 10f + dy)).forEach { (action, x, y) ->
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
        }
    }
    private fun tap(view: View) {
        val xy = IntArray(2)
        inst.runOnMainSync { view.getLocationOnScreen(xy) }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, xy[0] + view.width / 2f, xy[1] + view.height / 2f, 0).also {
                it.source = InputDevice.SOURCE_TOUCHSCREEN; assertTrue(inst.uiAutomation.injectInputEvent(it, true)); it.recycle()
            }
        }
        inst.waitForIdleSync()
    }
    private fun underlying(onClick: () -> Unit): Pair<Activity, Button> {
        app.settings.update { it.copy(onboardingDone = true, hudEnabled = false) }
        val activity = inst.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var button: Button
        inst.runOnMainSync {
            val frame = FrameLayout(activity)
            button = Button(activity).apply { text = "Underlying action"; setOnClickListener { onClick() } }
            frame.addView(button, FrameLayout.LayoutParams(-1, 150, Gravity.BOTTOM).apply { bottomMargin = 160 })
            activity.setContentView(frame)
        }
        inst.waitForIdleSync()
        return activity to button
    }
    private fun geometry(activity: Activity, root: View) {
        inst.runOnMainSync {
            val params = root.layoutParams as WindowManager.LayoutParams
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            params.width = 780; params.height = 700; params.x = 30; params.y = 150
            activity.getSystemService(WindowManager::class.java).updateViewLayout(root, params)
            drag(find(root, "Move floating window") ?: error("Missing move control"), 40f, 35f)
            assertEquals(70, params.x); assertEquals(185, params.y)
            drag(find(root, "Resize floating window") ?: error("Missing resize control"), 90f, 100f)
            assertEquals(870, params.width); assertEquals(800, params.height)
        }
        inst.waitForIdleSync()
    }
    @Test fun reviewPopupMovesResizesPassesTapsAndRequiresExplicitAction() {
        val settings = app.settings.settings.value
        var count = 0; var approved = false
        val (activity, button) = underlying { count++ }
        lateinit var panel: FloatingPanel
        try {
            inst.runOnMainSync { panel = FloatingPanel.review(activity, "Fixture review", "Exact target and undo are reviewed before execution.", "Confirm test action") { approved = true } }
            inst.waitForIdleSync()
            geometry(activity, panel.rootView())
            tap(button)
            assertEquals(1, count); assertFalse(approved); assertTrue(panel.rootView().isAttachedToWindow)
            inst.runOnMainSync { find(panel.rootView(), "Confirm test action")!!.performClick() }
            inst.waitForIdleSync()
            assertTrue(approved); assertFalse(panel.rootView().isAttachedToWindow)
        } finally { inst.runOnMainSync { runCatching { panel.dismiss() }; activity.finish() }; app.settings.update { settings } }
    }
    @Test fun paletteAndQuickHudAreBoundedMovableResizableAndNonModal() {
        val settings = app.settings.settings.value
        var count = 0
        val (underlying, button) = underlying { count++ }
        try {
            for (name in listOf("com.mcpintelligence.fr3k.ui.palette.CommandPaletteActivity", "com.mcpintelligence.fr3k.hud.quickhud.QuickHudActivity", "com.mcpintelligence.fr3k.share.ShareReceiverActivity", "com.mcpintelligence.fr3k.hud.LongPressRadialActivity")) {
                val popup = inst.startActivitySync(Intent().setClassName(app, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                SystemClock.sleep(700); inst.waitForIdleSync()
                inst.runOnMainSync { popup.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(popup.window.decorView.windowToken, 0) }
                SystemClock.sleep(400)
                geometry(popup, popup.window.decorView)
                val before = count
                tap(button)
                assertEquals("Outside touch with $name open", before + 1, count)
                inst.runOnMainSync { popup.finish() }
                inst.waitForIdleSync()
            }
        } finally { inst.runOnMainSync { underlying.finish() }; app.settings.update { settings } }
    }
}
