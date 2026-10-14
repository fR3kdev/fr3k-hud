package com.mcpintelligence.fr3k.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.ConsentLevel
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.hud.HudOverlayService
import com.mcpintelligence.fr3k.hud.quickhud.QuickHudActivity
import com.mcpintelligence.fr3k.protocol.Capability
import com.mcpintelligence.fr3k.protocol.DeviceManifest
import com.mcpintelligence.fr3k.protocol.DeviceStatus
import com.mcpintelligence.fr3k.ui.ask.AskAboutThisActivity
import com.mcpintelligence.fr3k.ui.automation.AutomationActivity
import com.mcpintelligence.fr3k.ui.blackwave.BlackwaveActivity
import com.mcpintelligence.fr3k.ui.clipboard.SmartClipboardActivity
import com.mcpintelligence.fr3k.ui.devoverlay.DeveloperOverlayActivity
import com.mcpintelligence.fr3k.ui.diagnostics.DiagnosticsActivity
import com.mcpintelligence.fr3k.ui.handoff.DeviceHandoffActivity
import com.mcpintelligence.fr3k.ui.integrations.IntegrationsActivity
import com.mcpintelligence.fr3k.ui.onboarding.OnboardingActivity
import com.mcpintelligence.fr3k.ui.palette.CommandPaletteActivity
import com.mcpintelligence.fr3k.ui.screenshot.ScreenshotActivity
import com.mcpintelligence.fr3k.ui.settings.SettingsActivity

/**
 * The main dashboard — single-pane application shell.
 *
 * Top: identity + profile-aware suggestions (§39/§40)
 * Actions: command palette + ask-about-this + smart-clipboard + screenshot
 * Inventory: capabilities + commands + fleet
 * Diagnostics: observability panel + settings
 * Plus the orb / Quick Settings tile entry point.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // §9 first-run gate: a fresh install (onboardingDone = false) is sent
        // to the setup wizard. We deliberately DON'T finish(): the dashboard
        // inflates underneath and is revealed the moment setup completes.
        if (!Fr3kApplication.get().settings.settings.value.onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        val activity = this
        setContent {
            Fr3kTheme {
                Box(modifier = Modifier.fillMaxSize().background(Fr3kPalette.Bg)) {
                    CommandDeck(
                        activity = activity,
                        onOpenPalette = { activity.startActivity(Intent(activity, CommandPaletteActivity::class.java)) },
                        onAskAboutThis = { activity.startActivity(Intent(activity, AskAboutThisActivity::class.java)) },
                    )
                }
            }
        }
        // Auto-start the HUD service if overlay + notification perms are
        // already granted. The orb should appear on app launch without the
        // user having to tap "Start". If perms are missing, the user can
        // still tap "Start" from the dashboard to grant them.
        try {
            val canOverlay = com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher.canDrawOverlays(this)
            val canNotif = android.os.Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (canOverlay && canNotif && Fr3kApplication.get().settings.settings.value.hudEnabled) {
                android.util.Log.i("FR3K", "auto-starting HudOverlayService from MainActivity.onCreate")
                startForegroundService(Intent(this, HudOverlayService::class.java))
            } else {
                android.util.Log.i("FR3K", "skipping auto-start (overlay=$canOverlay, notif=$canNotif)")
            }
        } catch (t: Throwable) {
            android.util.Log.e("FR3K", "auto-start failed", t)
        }
        // Shortcut: if launched with EXTRA_OPEN_INTEGRATIONS, jump
        // straight to the integrations panel. Useful for adb testing
        // and for deep links from the system Settings "Manage apps"
        // screen so users can land on the right page after granting
        // a permission.
        openRequestedSurface(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A deep link delivered to an already-running dashboard must still land: without this, launching
        // the same shortcut twice silently does nothing.
        openRequestedSurface(intent)
    }

    private fun openRequestedSurface(intent: Intent?) {
        if (intent?.getBooleanExtra("open_integrations", false) == true) {
            startActivity(Intent(this, IntegrationsActivity::class.java))
        }
        // Same shortcut for the guides, so `adb am start --ez open_mods true` lands on APP MODS.
        if (intent?.getBooleanExtra("open_mods", false) == true) {
            startActivity(Intent(this, com.mcpintelligence.fr3k.ui.mods.ModsActivity::class.java))
        }
        // APP CONTROL is not exported, so a verification script reaches it the way a user does: through
        // the Deck. `adb am start --ez open_app_control true` opens it on the real product path.
        if (intent?.getBooleanExtra("open_app_control", false) == true) {
            startActivity(Intent(this, com.mcpintelligence.fr3k.ui.appcontrol.AppControlActivity::class.java))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Notifications granted — start the HUD now if overlay was already OK.
            // Use startForegroundService, NOT startService: the HUD is a foreground
            // service on API 26+ and plain startService will throw
            // IllegalStateException.
            if (com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher.canDrawOverlays(this)) {
                startForegroundService(Intent(this, HudOverlayService::class.java))
            }
        }
    }

    companion object {
        const val REQ_NOTIF = 7001
    }
}
