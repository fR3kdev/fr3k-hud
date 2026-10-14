package com.mcpintelligence.fr3k.location

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import com.mcpintelligence.fr3k.core.CommandResult
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/** Foreground, permission-aware GPS actions. No synthetic coordinates or queued transport success. */
class LocationActions(private val context: Context) {
    suspend fun execute(action: String): CommandResult {
        if (action == "waypoint") return CommandResult.Failed("No waypoint transport is connected in this build. Nothing sent.")
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return CommandResult.Failed("Grant Location permission in Diagnostics, enable Android Location, then retry.")
        val manager = context.getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled) return CommandResult.Failed("Android Location is off. Enable it in Settings, then retry.")
        val fix = try {
            val known = manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.elapsedRealtimeNanos }
            if (known != null && (android.os.SystemClock.elapsedRealtimeNanos() - known.elapsedRealtimeNanos) < 120_000_000_000L) known
            else withTimeoutOrNull(12000) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val cancellation = CancellationSignal()
                    continuation.invokeOnCancellation { cancellation.cancel() }
                    val provider = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                    manager.getCurrentLocation(provider, cancellation, context.mainExecutor) { if (continuation.isActive) continuation.resume(it) }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { return CommandResult.Failed("Could not read location: ${error.message}") }
        if (fix == null) return CommandResult.Failed("No recent location fix. Move to GPS coverage and retry; no coordinates invented.")
        val coordinates = "${fix.latitude}, ${fix.longitude}"
        return withContext(Dispatchers.Main) {
            when (action) {
                "copy" -> {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("GPS coordinates", coordinates))
                    CommandResult.Ok("Copied observed coordinates: $coordinates (accuracy ${fix.accuracy}m)", mapOf("clipboard" to coordinates))
                }
                "share" -> {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, coordinates), "Share observed location").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    CommandResult.Ok("Opened Android's chooser with observed coordinates. Nothing sent until you choose a recipient.")
                }
                else -> CommandResult.Failed("Unknown location action")
            }
        }
    }
}
