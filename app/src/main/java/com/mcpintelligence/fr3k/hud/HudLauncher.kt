package com.mcpintelligence.fr3k.hud

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher

/**
 * Single place that starts and stops the floating HUD.
 *
 * The overlay is an explicit opt-in: it must not appear on app launch or after a reboot until the
 * operator turned it on, so every caller goes through [start] instead of firing the service itself.
 */
object HudLauncher {
    fun isRunning(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.getRunningServices(200).any { it.service.className == HudOverlayService::class.java.name }
    }.getOrDefault(false)

    /** Both permissions the foreground service needs before it can show the orb. */
    fun missingPermission(context: Context): String? = when {
        !SpecialPermissionLauncher.canDrawOverlays(context) -> "overlay"
        Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED -> "notifications"
        else -> null
    }

    /** Returns true only when the service was actually told to start. */
    fun start(context: Context): Boolean {
        if (missingPermission(context) != null) return false
        return runCatching {
            ContextCompat.startForegroundService(context, Intent(context, HudOverlayService::class.java))
            true
        }.getOrDefault(false)
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, HudOverlayService::class.java)) }
    }
}
