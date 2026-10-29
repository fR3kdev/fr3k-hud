package com.mcpintelligence.fr3k.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Central permission registry (§34 doctrine — user must always be able to
 * inspect what will be requested; we auto-request only what a feature needs).
 *
 * The brief's per-feature requirements:
 *  - HUD orb           → SYSTEM_ALERT_WINDOW + POST_NOTIFICATIONS
 *  - Speech-to-text    → RECORD_AUDIO
 *  - Screenshot        → MediaProjection (handled by user gesture, not runtime)
 *  - GPS               → ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION
 *  - Termux bridge     → com.termux.permission.RUN_COMMAND
 *  - Clipboard         → no perm (read-on-invocation)
 *  - Notification ls   → BIND_NOTIFICATION_LISTENER
 *  - Bluetooth scan    → BLUETOOTH_CONNECT / BLUETOOTH_SCAN
 *  - Storage / files   → READ_MEDIA_IMAGES etc on 33+
 *
 * Each feature calls [request] with its id and the activity. The activity
 * implements [Host] to receive the result.
 */
object PermissionRegistry {

    /**
     * All runtime permission strings the app declares in its manifest.
     *  Feature → list of Android runtime permissions it needs. */
    fun permissionsFor(feature: Feature): List<String> = when (feature) {
        Feature.HUD_ORB -> listOf(Manifest.permission.POST_NOTIFICATIONS).filter { Build.VERSION.SDK_INT >= 33 }
        Feature.STT -> listOf(Manifest.permission.RECORD_AUDIO)
        Feature.GPS -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        Feature.BLUETOOTH -> {
            val list = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31) {
                list += Manifest.permission.BLUETOOTH_CONNECT
                list += Manifest.permission.BLUETOOTH_SCAN
            }
            list
        }
        Feature.STORAGE -> if (Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        Feature.MIC_PROJECTION -> emptyList() // MediaProjection is gesture-driven
        Feature.NOTIFICATION_LISTENER -> emptyList() // granted via Settings
        Feature.TERMUX -> listOf<String>().also { /* com.termux.permission.RUN_COMMAND is not a runtime perm — granted by Termux itself */ }
    }

    fun granted(context: Context, feature: Feature): Boolean =
        permissionsFor(feature).all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Issue a runtime permission request for [feature]. Returns the list of
     * permissions it actually requested (empty if everything was already
     * granted). The activity must be the caller.
     */
    fun request(activity: Activity, feature: Feature, requestCode: Int): List<String> {
        val needed = permissionsFor(feature).filter { perm ->
            ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, needed.toTypedArray(), requestCode)
        }
        return needed
    }

    /**
     * Same as [request] but for features that need both runtime perms AND
     * a special-permission Settings round-trip (overlay, notif listener,
     * usage stats). Returns the runtime perms it asked for; the caller is
     * responsible for launching the Settings intent via
     * [SpecialPermissionLauncher].
     */
    fun requestRuntime(activity: Activity, feature: Feature, requestCode: Int): List<String> =
        request(activity, feature, requestCode)

    /**
     * Returns the first ungranted runtime permission for [feature], or null
     * if everything is granted. Useful for showing a one-line explanation
     * before the OS dialog.
     */
    fun firstMissing(context: Context, feature: Feature): String? =
        permissionsFor(feature).firstOrNull { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }

    /**
     * All Android runtime (dangerous) permissions the app actually declares
     * in its manifest.
     *
     * Derived from `PackageManager` instead of the `Feature` matrix so a
     * newly declared runtime permission can never be silently omitted from
     * the "GRANT ALL" flow. The matrix below only governs the *per-feature*
     * buttons; this is the authoritative set for the one-tap grant.
     *
     * Previously this walked `Feature.entries`, which meant any runtime
     * permission declared in the manifest but not attached to a feature —
     * CAMERA was exactly such a case — was excluded, so "GRANT ALL" never
     * actually granted everything.
     */
    fun allDeclaredRuntimePermissions(context: Context): List<String> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val requested = runCatching {
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
        }.getOrNull() ?: return emptyList()
        return requested.filter { perm ->
            // Only dangerous permissions are runtime-grantable.
            val dangerous = runCatching {
                val info = pm.getPermissionInfo(perm, 0)
                (info.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE) ==
                    android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(false)
            // Drop permissions the current SDK no longer grants via the
            // runtime dialog even though they remain in the manifest
            // (READ_EXTERNAL_STORAGE is superseded by READ_MEDIA_* on 33+).
            dangerous && isGrantableOnThisSdk(perm)
        }
    }

    /**
     * Permissions that are declared for older SDKs (with `maxSdkVersion`)
     * but are no longer runtime-requestable on the running platform.
     * Requesting them returns an immediate denial with no dialog, which
     * looked like a dead button.
     */
    private fun isGrantableOnThisSdk(perm: String): Boolean = when (perm) {
        Manifest.permission.READ_EXTERNAL_STORAGE -> Build.VERSION.SDK_INT < 33
        Manifest.permission.WRITE_EXTERNAL_STORAGE -> Build.VERSION.SDK_INT < 30
        else -> true
    }

    /**
     * The subset of [allDeclaredRuntimePermissions] that haven't been
     * granted yet, on the current SDK. On <= 22 we just declare the list
     * because everything is granted at install.
     */
    fun runtimeNotGranted(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < 23) return emptyList()
        return allDeclaredRuntimePermissions(context).filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
    }

    enum class Feature {
        HUD_ORB, STT, GPS, BLUETOOTH, STORAGE, MIC_PROJECTION, NOTIFICATION_LISTENER,
        TERMUX,
    }
}
