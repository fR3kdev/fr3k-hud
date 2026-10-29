package com.mcpintelligence.fr3k.hud.overlays

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.mcpintelligence.fr3k.hud.HudOverlayService
import com.mcpintelligence.fr3k.ui.MainActivity

/**
 * Minimal, expandable action menu anchored to the HUD orb and shown on orb tap.
 *
 * Design intent: the orb itself stays tiny when collapsed, so the overlay uses
 * next to no screen space. A tap expands a compact chip panel beside the orb;
 * a "MORE" row expands it further. Nothing here is a fullscreen activity — it is
 * a `TYPE_APPLICATION_OVERLAY` window so the user's current app stays visible.
 *
 * Primary (always visible): CHAT, BROWSER, TERMINAL.
 * Expanded (behind MORE): Quick HUD, Devices, Ask, Integrations, Diagnostics,
 * Orb appearance, Settings, FR3K Main.
 */
class Fr3kOrbQuickMenu(
    private val host: OverlayHost,
    private val anchor: () -> Pair<Int, Int>,
    private val orbSizePx: () -> Int,
    private val onOpenAppearance: () -> Unit,
) : Fr3kOverlay {

    override val name: String = "orb-quick-menu"
    override var isAttached: Boolean = false
        private set

    private val density = host.context.resources.displayMetrics.density
    private val panelWidthPx = (216 * density).toInt()

    private val root: View
    private val params: WindowManager.LayoutParams
    private val extraContainer: LinearLayout
    private val expandToggle: TextView
    private var expanded = false

    init {
        val ctx = host.context
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(0xF0111126.toInt())
                setStroke((1.5f * density).toInt(), 0xFF2b2b40.toInt())
            }
        }

        panel.addView(TextView(ctx).apply {
            text = "FR3K ▸ HUD"
            setTextColor(0xFF7d3cff.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
        }, lpMatch())

        val primary = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(primary, lpMatch())
        addOverlayRow(primary, "◍  CHAT", HudOverlayService.ACTION_OPEN_CHAT)
        addOverlayRow(primary, "◍  BROWSER", HudOverlayService.ACTION_OPEN_BROWSER)
        addOverlayRow(primary, "◍  TERMINAL", HudOverlayService.ACTION_OPEN_TERMINAL)

        extraContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        panel.addView(extraContainer, lpMatch())
        addActivityRow(extraContainer, "◍  QUICK HUD", com.mcpintelligence.fr3k.hud.quickhud.QuickHudActivity::class.java)
        addActivityRow(extraContainer, "◍  DEVICES", com.mcpintelligence.fr3k.ui.blackwave.BlackwaveActivity::class.java)
        addActivityRow(extraContainer, "◍  ASK ABOUT THIS", com.mcpintelligence.fr3k.ui.ask.AskAboutThisActivity::class.java)
        addActivityRow(extraContainer, "◍  INTEGRATIONS", com.mcpintelligence.fr3k.ui.integrations.IntegrationsActivity::class.java)
        addActivityRow(extraContainer, "◍  DIAGNOSTICS", com.mcpintelligence.fr3k.ui.diagnostics.DiagnosticsActivity::class.java)
        addOverlayRow(extraContainer, "◍  ORB APPEARANCE", "", onClick = { hide(); onOpenAppearance() })
        addActivityRow(extraContainer, "◍  SETTINGS", com.mcpintelligence.fr3k.ui.settings.SettingsActivity::class.java)
        addActivityRow(extraContainer, "◍  FR3K MAIN", MainActivity::class.java)

        expandToggle = makeRow("▾  MORE") {
            expanded = !expanded
            extraContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            expandToggle.text = if (expanded) "▴  LESS" else "▾  MORE"
            host.update(root, params)
        }
        panel.addView(expandToggle, lpMatch())
        panel.addView(makeRow("✕  CLOSE") { hide() }, lpMatch())

        root = panel
        params = WindowManager.LayoutParams(
            panelWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 200
        }
    }

    private fun lpMatch() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun addOverlayRow(parent: LinearLayout, label: String, action: String, onClick: (() -> Unit)? = null) {
        parent.addView(makeRow(label) {
            hide()
            if (onClick != null) {
                onClick()
            } else {
                host.context.sendBroadcast(Intent(action).setPackage(host.context.packageName))
            }
        }, lpMatch())
    }

    private fun addActivityRow(parent: LinearLayout, label: String, activity: Class<*>) {
        parent.addView(makeRow(label) {
            hide()
            val i = Intent(host.context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { host.context.startActivity(i) }
        }, lpMatch())
    }

    private fun makeRow(label: String, onClick: () -> Unit): TextView {
        val ctx = host.context
        val bg = GradientDrawable().apply {
            cornerRadius = 8f * density
            setColor(0xFF11111c.toInt())
            setStroke((1.5f * density).toInt(), 0xFF2b2b40.toInt())
        }
        return TextView(ctx).apply {
            text = label
            setTextColor(0xFFcdd1e0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setPadding((16 * density).toInt(), (11 * density).toInt(), (16 * density).toInt(), (11 * density).toInt())
            background = bg
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = (5 * density).toInt()
            layoutParams = lp
        }
    }

    override fun show() {
        if (isAttached) return
        val (ox, oy) = anchor()
        val metrics = host.context.resources.displayMetrics
        params.x = (ox + orbSizePx() + (12 * density).toInt())
            .coerceIn(0, (metrics.widthPixels - panelWidthPx).coerceAtLeast(0))
        params.y = oy.coerceIn(0, (metrics.heightPixels - (360 * density).toInt()).coerceAtLeast(0))
        try {
            host.add(root, params)
            isAttached = true
        } catch (_: Throwable) { /* already attached or WM rejected */ }
    }

    override fun hide() {
        if (!isAttached) return
        expanded = false
        extraContainer.visibility = View.GONE
        expandToggle.text = "▾  MORE"
        host.remove(root)
        isAttached = false
    }
}
