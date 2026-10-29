package com.mcpintelligence.fr3k.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher

/**
 * Android side of the setup walkthrough: reads the real device facts, opens the exact screen for
 * each item, and remembers what the operator has settled.
 */
object SetupAndroid {

    private const val PREFS = "fr3k_setup_walkthrough"

    fun progress(context: Context): Map<String, SetupStatus> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            val status = runCatching { SetupStatus.valueOf(value as String) }.getOrNull() ?: return@mapNotNull null
            key to status
        }.toMap()

    fun mark(context: Context, id: String, status: SetupStatus) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(id, status.name).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Device facts, combining what the voice stack reported with what the system exposes. */
    fun facts(
        context: Context,
        microphone: Boolean,
        tts: Boolean,
        recogniser: Boolean,
        provider: Boolean,
    ): SetupFacts = SetupFacts(
        microphone = microphone,
        notifications = notificationsGranted(context),
        overlay = runCatching { SpecialPermissionLauncher.canDrawOverlays(context) }.getOrDefault(false),
        tts = tts,
        recogniser = recogniser,
        provider = provider,
        developerOptions = developmentSettingsEnabled(context),
        wirelessDebugging = adbEnabled(context),
        termuxInstalled = installed(context, "com.termux"),
        termuxApi = installed(context, "com.termux.api"),
        termuxBoot = installed(context, "com.termux.boot"),
        termuxStorage = installed(context, "com.termux") && storageLinked(context),
        shizukuRunning = installed(context, "moe.shizuku.privileged.api"),
        batteryUnrestricted = batteryUnrestricted(context),
        voiceTuned = com.mcpintelligence.fr3k.voice.VoiceTuning.tuned(context),
    )

    /**
     * The screen that satisfies an item. Developer options route through the About page so the
     * operator can be told to tap Build number seven times, which is the only way to enable them.
     */
    fun intentFor(context: Context, itemId: String): Intent? = when (itemId) {
        "notifications" -> appDetails(context).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        "developer_options" -> if (developmentSettingsEnabled(context)) {
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        } else {
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        }
        "wireless_debugging" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        "termux", "termux_api", "termux_boot" -> Intent(Intent.ACTION_VIEW, Uri.parse(fdroidFor(itemId)))
        "termux_storage" -> context.packageManager.getLaunchIntentForPackage("com.termux")
        "shizuku" -> context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases"))
        "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        "tts", "voice_tuning" -> Intent("com.android.settings.TTS_SETTINGS")
        "microphone", "recogniser" -> if (itemId == "microphone") appDetails(context) else null
        else -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** True when the operator cannot do this on this device at all (no developer options possible). */
    fun isBlocked(item: SetupItem, facts: SetupFacts): Boolean =
        item.needsDeveloperOptions && !facts.developerOptions

    private fun fdroidFor(id: String) = when (id) {
        "termux_api" -> "https://f-droid.org/packages/com.termux.api/"
        "termux_boot" -> "https://f-droid.org/packages/com.termux.boot/"
        else -> "https://f-droid.org/packages/com.termux/"
    }

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun notificationsGranted(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun developmentSettingsEnabled(context: Context): Boolean =
        runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        }.getOrDefault(false)

    private fun adbEnabled(context: Context): Boolean =
        runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)

    /**
     * Shared storage is optional: FR3K HUD only uses Termux's private $HOME, and `~/storage` is a
     * set of symlinks we cannot inspect from another app's sandbox. Assuming "not linked" made the
     * walkthrough nag forever even after the operator ran termux-setup-storage.
     */
    private fun storageLinked(context: Context): Boolean = true

    private fun batteryUnrestricted(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(android.os.PowerManager::class.java)
        manager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)
}
