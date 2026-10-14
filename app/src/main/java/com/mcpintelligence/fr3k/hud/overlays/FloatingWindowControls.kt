package com.mcpintelligence.fr3k.hud.overlays

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/** Shared geometry controls. Window coordinates use TOP|START, never a fullscreen scrim. */
object FloatingWindowControls {
    fun install(host: OverlayHost, root: View, params: WindowManager.LayoutParams, handle: View, grip: View) {
        val density = root.resources.displayMetrics.density
        fun clamp() {
            val bounds = host.windowManager.maximumWindowMetrics.bounds
            params.width = params.width.coerceIn((220 * density).toInt().coerceAtMost(bounds.width()), bounds.width())
            params.height = params.height.coerceIn((180 * density).toInt().coerceAtMost(bounds.height()), (bounds.height() - 48 * density).toInt().coerceAtLeast(1))
            params.x = params.x.coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
            params.y = params.y.coerceIn((24 * density).toInt(), (bounds.height() - params.height - 24 * density).toInt().coerceAtLeast((24 * density).toInt()))
            host.update(root, params)
        }
        fun gesture(view: View, resize: Boolean) {
            var x = 0f; var y = 0f; var startX = 0; var startY = 0; var w = 0; var h = 0
            view.isClickable = true
            view.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        x = event.rawX; y = event.rawY; startX = params.x; startY = params.y
                        w = params.width.takeIf { it > 0 } ?: root.width
                        h = params.height.takeIf { it > 0 } ?: root.height
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.gravity = Gravity.TOP or Gravity.START
                        if (resize) { params.width = w + (event.rawX - x).toInt(); params.height = h + (event.rawY - y).toInt() }
                        else { params.x = startX + (event.rawX - x).toInt(); params.y = startY + (event.rawY - y).toInt() }
                        clamp(); true
                    }
                    MotionEvent.ACTION_UP -> { v.performClick(); true }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }
        handle.contentDescription = "Move floating window"
        grip.contentDescription = "Resize floating window"
        gesture(handle, false); gesture(grip, true)
        listOf("Move left" to (-48 to 0), "Move right" to (48 to 0), "Move up" to (0 to -48), "Move down" to (0 to 48)).forEach { (label, delta) ->
            ViewCompat.addAccessibilityAction(handle, label) { _, _ -> params.x += (delta.first * density).toInt(); params.y += (delta.second * density).toInt(); clamp(); true }
        }
        ViewCompat.addAccessibilityAction(grip, "Enlarge window") { _, _ -> params.width += (48 * density).toInt(); params.height += (48 * density).toInt(); clamp(); true }
        ViewCompat.addAccessibilityAction(grip, "Shrink window") { _, _ -> params.width -= (48 * density).toInt(); params.height -= (48 * density).toInt(); clamp(); true }
    }
}
