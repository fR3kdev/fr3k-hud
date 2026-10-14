package com.mcpintelligence.fr3k.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import com.mcpintelligence.fr3k.hud.overlays.*

/** Movable, resizable review/help panel. Outside touches pass through; no dimming scrim. */
class FloatingPanel(private val activity: Activity, title: String, content: View) {
    private val host = OverlayHost(activity, activity.getSystemService(WindowManager::class.java))
    private val density = activity.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat(); setColor(0xFF151B30.toInt()); setStroke(dp(1), 0xFF6576A0.toInt())
        }
        elevation = dp(12).toFloat()
    }
    private val params = OverlayParams.make(
        width = dp(340).coerceAtMost(host.windowManager.currentWindowMetrics.bounds.width() - dp(16)),
        height = dp(440).coerceAtMost((host.windowManager.currentWindowMetrics.bounds.height() * 0.75).toInt()),
        type = if (android.provider.Settings.canDrawOverlays(activity)) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
    ).apply {
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        x = dp(8); y = dp(100)
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL) token = activity.window.decorView.windowToken
    }
    init {
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val handle = TextView(activity).apply { text = "$title · move"; textSize = 16f; setTextColor(0xFFC5A0FF.toInt()); minHeight = dp(48) }
        header.addView(handle, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(activity).apply { text = "×"; contentDescription = "Close floating panel"; setOnClickListener { dismiss() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val grip = TextView(activity).apply { text = "↘ Resize"; gravity = Gravity.END or Gravity.CENTER_VERTICAL; minHeight = dp(48); setTextColor(0xFFC5A0FF.toInt()) }
        root.addView(grip)
        FloatingWindowControls.install(host, root, params, handle, grip)
    }
    fun show(): FloatingPanel { host.add(root, params); return this }
    fun dismiss() { host.remove(root) }
    fun rootView(): View = root

    companion object {
        fun review(activity: Activity, title: String, body: String, actionLabel: String, action: () -> Unit): FloatingPanel {
            val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            content.addView(ScrollView(activity).apply { addView(TextView(activity).apply { text = body; textSize = 15f; setTextColor(0xFFE6ECF5.toInt()); setTextIsSelectable(true) }) }, LinearLayout.LayoutParams(-1, 0, 1f))
            lateinit var panel: FloatingPanel
            content.addView(Button(activity).apply { text = actionLabel; setOnClickListener { panel.dismiss(); action() } })
            panel = FloatingPanel(activity, title, content)
            return panel.show()
        }
    }
}
