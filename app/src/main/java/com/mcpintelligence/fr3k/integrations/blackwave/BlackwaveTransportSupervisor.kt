package com.mcpintelligence.fr3k.integrations.blackwave

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Long-lived BLACKWAVE transport supervisor.
 *
 * Wireless health is authoritative. USB is opened only after genuine
 * reachability loss and is used solely to request restoration of wireless.
 */
class BlackwaveTransportSupervisor(
    private val bridge: BlackwaveBridgeClient,
    private val usb: BlackwaveUsbRecoveryLink,
    private val pollIntervalMs: Long = 5_000L,
    private val reconnectAttemptIntervalMs: Long = 15_000L,
) {
    private val policy = BlackwaveTransportPolicy(
        failureThreshold = 2,
        recoveryThreshold = 1,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(policy.snapshot())
    val state: StateFlow<TransportSnapshot> = _state.asStateFlow()

    private var job: Job? = null
    private var lastReconnectAttemptMs = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                tick()
                delay(pollIntervalMs)
            }
        }
    }

    suspend fun tick() {
        val wireless = bridge.probeHealth()
        val shouldProbeUsb = wireless == WirelessHealth.UNREACHABLE ||
            _state.value.state == BlackwaveLinkState.USB_FALLBACK ||
            _state.value.state == BlackwaveLinkState.WIRELESS_RECOVERING
        val usbReady = if (shouldProbeUsb) usb.probeReady() else false
        val snapshot = policy.observe(wireless, usbReady)
        _state.value = snapshot

        if (snapshot.state == BlackwaveLinkState.USB_FALLBACK) {
            maybeRequestWirelessReconnect()
        }
    }

    private fun maybeRequestWirelessReconnect() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReconnectAttemptMs < reconnectAttemptIntervalMs) return
        lastReconnectAttemptMs = now
        usb.requestWirelessReconnect()
    }

    fun requestUsbPermission(): Boolean = usb.requestPermission()

    fun usbStatus(): UsbRecoveryLinkStatus = usb.status

    fun stop() {
        job?.cancel()
        job = null
        usb.close()
        scope.cancel()
    }
}
