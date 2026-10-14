package com.mcpintelligence.fr3k.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import com.mcpintelligence.fr3k.hud.overlays.*

/** Bounded non-modal activity windows for legacy popup entry points. */
fun makeActivityFloating(activity: Activity, title: String) {
    val window = activity.window
    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    val density = activity.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val bounds = activity.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
    window.setGravity(Gravity.TOP or Gravity.START)
    window.setLayout(dp(340).coerceAtMost(bounds.width() - dp(16)), dp(520).coerceAtMost((bounds.height() * 0.8f).toInt()))
    window.attributes = window.attributes.apply { x = dp(8); y = dp(60); dimAmount = 0f }
    window.decorView.post {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        if (content.childCount == 0) return@post
        val body = content.getChildAt(0)
        content.removeView(body)
        val frame = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xFF151B30.toInt()); setStroke(dp(1), 0xFF6576A0.toInt()) }
            clipToOutline = true
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val handle = TextView(activity).apply { text = "$title · move"; textSize = 14f; minHeight = dp(48); setPadding(dp(12), dp(8), 0, 0); setTextColor(0xFFC5A0FF.toInt()) }
        row.addView(handle, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(activity).apply { text = "×"; contentDescription = "Close floating panel"; setOnClickListener { activity.finish() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        frame.addView(row)
        frame.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val grip = TextView(activity).apply { text = "↘ Resize"; gravity = Gravity.END or Gravity.CENTER_VERTICAL; minHeight = dp(48); setPadding(0, 0, dp(12), 0); setTextColor(0xFFC5A0FF.toInt()) }
        frame.addView(grip)
        content.addView(frame, ViewGroup.LayoutParams(-1, -1))
        val params = window.decorView.layoutParams as? WindowManager.LayoutParams ?: return@post
        FloatingWindowControls.install(OverlayHost(activity, activity.getSystemService(WindowManager::class.java)), window.decorView, params, handle, grip)
    }
}
