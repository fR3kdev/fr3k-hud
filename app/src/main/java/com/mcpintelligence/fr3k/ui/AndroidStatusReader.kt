package com.mcpintelligence.fr3k.ui

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.bluetooth.BluetoothManager
import com.mcpintelligence.fr3k.core.CommandResult

class AndroidStatusReader(private val context: Context) {
    fun read(kind: String): CommandResult = try {
        val text = when (kind) {
            "battery" -> context.getSystemService(BatteryManager::class.java).let {
                "Battery: ${it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}% · ${if (it.isCharging) "charging" else "not charging"}"
            }
            "network" -> context.getSystemService(ConnectivityManager::class.java).let { manager ->
                val caps = manager.getNetworkCapabilities(manager.activeNetwork)
                "Network: " + if (caps == null) "no active network" else {
                    val transport = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                        else -> "other"
                    }
                    "$transport · ${if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "internet validated" else "internet not validated"}"
                }
            }
            "bluetooth" -> {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                if (adapter == null) "Bluetooth: no adapter" else "Bluetooth: ${if (adapter.isEnabled) "enabled" else "disabled"}"
            }
            "storage" -> StatFs(context.filesDir.absolutePath).let { "App storage: ${it.availableBytes} bytes available / ${it.totalBytes} bytes total" }
            else -> return CommandResult.Failed("Unknown status query")
        }
        CommandResult.Ok(text)
    } catch (denied: SecurityException) { CommandResult.Failed("Permission required for $kind. Grant it in Diagnostics; no state invented.") }
      catch (error: Exception) { CommandResult.Failed("Status query failed: ${error.message}") }
}
