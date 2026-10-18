package com.mcpintelligence.fr3k.mcp

import android.content.Intent
import android.content.ComponentName
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.bridge.Fr3kBridgeService
import com.mcpintelligence.fr3k.bridge.UiTreeReader
import com.mcpintelligence.fr3k.capability.UiNode
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** App-only diagnostic MCP surface; bearer alone cannot enable UI changes. */
class HudDiagnosticSurface(private val app: Fr3kApplication, private val server: Fr3kMcpServer) {
    private val screens = linkedMapOf(
        "deck" to "MainActivity", "guides" to "mods.ModsActivity",
        "appmod" to "appcontrol.AppModDiscoveryActivity",
        "appcontrol" to "appcontrol.AppControlActivity",
        "patch" to "appcontrol.SelectedAppPatchActivity",
        "settings" to "settings.SettingsActivity",
        "onboarding" to "onboarding.OnboardingActivity",
        "integrations" to "integrations.IntegrationsActivity",
        "wireless" to "integrations.WirelessDebuggingActivity",
        "diagnostics" to "diagnostics.DiagnosticsActivity",
        "automations" to "automation.AutomationActivity",
        "blackwave" to "blackwave.BlackwaveActivity",
        "blackwave_devices" to "blackwave.BlackwaveAddDeviceActivity",
        "blackwave_setup" to "blackwave.BlackwaveSetupActivity",
        "quick_hud" to "hud.quickhud.QuickHudActivity",
        "radial_overlay" to "hud.LongPressRadialActivity",
        "share_receiver" to "share.ShareReceiverActivity",
        "ask" to "ask.AskAboutThisActivity",
        "clipboard" to "clipboard.SmartClipboardActivity",
        "screenshot" to "screenshot.ScreenshotActivity",
        "palette" to "palette.CommandPaletteActivity",
        "handoff" to "handoff.DeviceHandoffActivity",
        "developer" to "devoverlay.DeveloperOverlayActivity",
    )
    @Volatile private var controlExpiryMs: Long = 0
    fun allowControlsTenMinutes() { controlExpiryMs = SystemClock.elapsedRealtime() + 600_000L }
    fun revokeControls() { controlExpiryMs = 0 }
    fun controlsEnabled(): Boolean = SystemClock.elapsedRealtime() < controlExpiryMs
    private fun fullClass(id: String): String {
        val suffix = screens[id] ?: ""
        val base = "com.mcpintelligence.fr3k."
        return if (suffix.startsWith("hud.") || suffix.startsWith("share.")) base + suffix
            else base + "ui." + suffix
    }
    private fun requiresExternalContext(id: String): Boolean =
        id == "radial_overlay" || id == "share_receiver"
    private fun exists(id: String): Boolean = screens.containsKey(id) && runCatching {
        app.packageManager.getActivityInfo(ComponentName(app.packageName, fullClass(id)), 0)
    }.isSuccess
    fun screens(): JSONObject {
        val arr = JSONArray()
        screens.keys.forEach { arr.put(JSONObject().put("id", it).put("manifestRegistered", exists(it))
            .put("runtimeVerified", false)
            .put("directLaunchSupported", !requiresExternalContext(it))) }
        return JSONObject().put("ok", true).put("screens", arr).put("count", arr.length())
            .put("scope", "Only internal HUD activities; an entry is not a runtime test")
    }
    fun status(): JSONObject {
        val bridge = Fr3kBridgeService.instanceOrNull()
        return JSONObject().put("ok", true).put("mcpRunning", server.running)
            .put("mcpPort", server.port())
            .put("bridgeConnected", bridge != null)
            .put("foregroundHud", bridge?.foregroundPackage() == app.packageName)
            .put("controlAuthorized", controlsEnabled())
            .put("scope", "authenticated localhost, HUD-only; no secrets returned")
    }
    fun selfTest(): JSONObject {
        val checks = JSONArray()
        fun put(id: String, passed: Boolean, evidence: String) {
            checks.put(JSONObject().put("id", id).put("status", if (passed) "PASS" else "BLOCKED")
                .put("evidence", evidence))
        }
        put("listener", server.running, "ServerSocket running status")
        put("tools", server.toolList().length() >= 21, "MCP inventory present")
        put("screens", screens.keys.all(::exists), "Installed activity manifest verified")
        put("accessibility", Fr3kBridgeService.connected(), "Live accessibility bridge status")
        return JSONObject().put("ok", true).put("checks", checks)
            .put("passed", (0 until checks.length()).count {
                checks.getJSONObject(it).getString("status") == "PASS"
            }).put("total", checks.length())
            .put("limitations", "Does not establish chat, individual button or real hardware success")
    }
    /** Redacted clickable-node inventory: no private text fields, no third-party app data. */
    fun inspectUi(): JSONObject {
        val bridge = Fr3kBridgeService.instanceOrNull() ?: return fail("Accessibility bridge offline")
        if (bridge.foregroundPackage() != app.packageName) return fail("Refusing data from other apps")
        val root = bridge.readTree() ?: return fail("No active accessibility tree")
        val arr = JSONArray()
        var seen = 0
        fun safeLabel(s: String?): String {
            val value = s.orEmpty().trim()
            return if (value.length > 100 || Regex("(?i)(Bearer |sk-[a-z0-9]{12,}|[a-f0-9]{28,})").containsMatchIn(value))
                "[redacted]" else value
        }
        fun label(n: UiNode): String {
            if (n.password || n.className?.contains("EditText", true) == true) return ""
            return safeLabel(n.contentDescription?.takeIf { it.isNotBlank() } ?: n.text
                ?: n.children.firstOrNull { !it.password && it.text?.isNotBlank() == true }?.text)
        }
        fun walk(n: UiNode, path: String) {
            if (seen++ >= 600) return
            if (n.clickable && !n.password) arr.put(JSONObject().put("path", path)
                .put("className", n.className ?: "").put("label", label(n)))
            n.children.forEachIndexed { i, child ->
                walk(child, if (path.isEmpty()) i.toString() else path + "." + i)
            }
        }
        walk(root, "")
        return JSONObject().put("ok", true).put("controls", arr).put("count", arr.length())
            .put("coverage", "Visible accessible nodes; no screenshots or private inputs")
    }
    fun openScreen(id: String): JSONObject {
        if (!controlsEnabled()) return fail("Enable HUD diagnostic controls locally (10-minute scope)")
        if (!exists(id)) return fail("Unrecognized or missing screen")
        if (requiresExternalContext(id)) return fail("Screen requires a system event/share input; unsafe to launch without it")
        app.startActivity(Intent().setClassName(app.packageName, fullClass(id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return JSONObject().put("ok", true).put("screen", id)
            .put("dispatch", "ACCEPTED").put("renderVerified", false)
    }
    fun clickControl(path: String): JSONObject {
        if (!controlsEnabled()) return fail("No local diagnostic approval")
        if (!path.matches(Regex("\\d+(\\.\\d+){0,24}"))) return fail("Invalid exact path")
        val bridge = Fr3kBridgeService.instanceOrNull() ?: return fail("Bridge disconnected")
        if (bridge.foregroundPackage() != app.packageName) return fail("HUD is not foreground")
        val latch = CountDownLatch(1)
        var response = fail("No result")
        Handler(Looper.getMainLooper()).post {
            response = runCatching {
                val node = UiTreeReader.nodeAtPath(bridge.liveRoot() ?: error("No root"),
                    path.split('.').map { it.toInt() }) ?: error("Node moved")
                if (!node.isClickable || !node.isEnabled) error("Control not clickable")
                val label = (node.contentDescription ?: node.text)?.toString().orEmpty()
                if (Regex("(?i)(install|uninstall|delete|remove|erase|reset|allow|grant|approve|send|pay|purchase|flash|root|save key|revoke)")
                    .containsMatchIn(label)) error("Sensitive control requires normal app approval")
                if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) error("Click rejected")
                JSONObject().put("ok", true).put("dispatch", "ACCEPTED").put("verified", false)
            }.getOrElse { fail(it.message ?: "UI error") }
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS)) return fail("UI timeout")
        return response
    }
    companion object {
        private fun fail(text: String): JSONObject = JSONObject().put("ok", false).put("error", text)
    }
}
