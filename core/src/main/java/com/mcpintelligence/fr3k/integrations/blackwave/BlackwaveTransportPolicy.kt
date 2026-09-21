package com.mcpintelligence.fr3k.integrations.blackwave

/**
 * Pure transport policy for BLACKWAVE.
 *
 * Wireless is always preferred. USB is a recovery bearer only and is never
 * selected for authentication, authorization, TLS trust, or protocol errors.
 */
enum class BlackwaveLinkState {
    WIRELESS_ACTIVE,
    WIRELESS_DEGRADED,
    USB_CONNECTING,
    USB_FALLBACK,
    WIRELESS_RECOVERING,
    OFFLINE,
    AUTH_FAILED,
    TRUST_FAILED,
    REMOTE_FAILED,
}

enum class WirelessHealth {
    HEALTHY,
    UNREACHABLE,
    AUTH_FAILED,
    TRUST_FAILED,
    REMOTE_FAILED,
}
data class TransportSnapshot(
    val state: BlackwaveLinkState = BlackwaveLinkState.OFFLINE,
    val activeTransport: String = "none",
    val reason: String = "not probed",
    val wirelessHealth: WirelessHealth = WirelessHealth.UNREACHABLE,
    val usbReady: Boolean = false,
    val consecutiveWirelessFailures: Int = 0,
    val consecutiveWirelessSuccesses: Int = 0,
)

/**
 * Stateful, deterministic failover controller.
 *
 * [failureThreshold] adds a small anti-flap debounce before USB takeover.
 * [recoveryThreshold] defaults to one authenticated success so wireless
 * resumes immediately while the USB cable may remain attached.
 */
class BlackwaveTransportPolicy(
    private val failureThreshold: Int = 2,
    private val recoveryThreshold: Int = 1,
) {
    init {
        require(failureThreshold >= 1)
        require(recoveryThreshold >= 1)
    }

    private var failures = 0
    private var successes = 0
    private var last = TransportSnapshot()
    @Synchronized
    fun observe(wireless: WirelessHealth, usbReady: Boolean): TransportSnapshot {
        if (wireless == WirelessHealth.AUTH_FAILED) {
            return publish(
                BlackwaveLinkState.AUTH_FAILED, "none",
                "wireless authentication/authorization failed; USB bypass forbidden",
                wireless, usbReady, resetFailures = true,
            )
        }
        if (wireless == WirelessHealth.TRUST_FAILED) {
            return publish(
                BlackwaveLinkState.TRUST_FAILED, "none",
                "wireless TLS trust/hostname verification failed; USB bypass forbidden",
                wireless, usbReady, resetFailures = true,
            )
        }
        if (wireless == WirelessHealth.REMOTE_FAILED) {
            return publish(
                BlackwaveLinkState.REMOTE_FAILED, "none",
                "wireless endpoint reachable but service/protocol failed; USB bypass forbidden",
                wireless, usbReady, resetFailures = true,
            )
        }

        return if (wireless == WirelessHealth.HEALTHY) {
            onWirelessHealthy(usbReady)
        } else {
            onWirelessUnreachable(usbReady)
        }
    }
    private fun onWirelessHealthy(usbReady: Boolean): TransportSnapshot {
        failures = 0
        successes += 1
        val wasUsb = last.state == BlackwaveLinkState.USB_FALLBACK ||
            last.state == BlackwaveLinkState.USB_CONNECTING ||
            last.state == BlackwaveLinkState.WIRELESS_RECOVERING

        if (wasUsb && successes < recoveryThreshold) {
            return publish(
                BlackwaveLinkState.WIRELESS_RECOVERING, "usb",
                "authenticated wireless recovered; confirming before handback",
                WirelessHealth.HEALTHY, usbReady,
            )
        }
        return publish(
            BlackwaveLinkState.WIRELESS_ACTIVE, "wireless",
            if (wasUsb) "authenticated wireless restored; USB remains standby"
            else "authenticated wireless healthy",
            WirelessHealth.HEALTHY, usbReady,
        )
    }

    private fun onWirelessUnreachable(usbReady: Boolean): TransportSnapshot {
        successes = 0
        failures += 1
        if (failures < failureThreshold) {
            return publish(
                BlackwaveLinkState.WIRELESS_DEGRADED, "wireless",
                "wireless reachability degraded; debounce before fallback",
                WirelessHealth.UNREACHABLE, usbReady,
            )
        }
        if (!usbReady) {
            return publish(
                BlackwaveLinkState.OFFLINE, "none",
                "wireless unreachable and no authenticated USB recovery peer",
                WirelessHealth.UNREACHABLE, usbReady,
            )
        }
        return publish(
            BlackwaveLinkState.USB_FALLBACK, "usb",
            "wireless unreachable; authenticated USB recovery bearer active",
            WirelessHealth.UNREACHABLE, usbReady,
        )
    }

    private fun publish(
        state: BlackwaveLinkState,
        active: String,
        reason: String,
        wireless: WirelessHealth,
        usbReady: Boolean,
        resetFailures: Boolean = false,
    ): TransportSnapshot {
        if (resetFailures) {
            failures = 0
            successes = 0
        }
        last = TransportSnapshot(
            state = state,
            activeTransport = active,
            reason = reason,
            wirelessHealth = wireless,
            usbReady = usbReady,
            consecutiveWirelessFailures = failures,
            consecutiveWirelessSuccesses = successes,
        )
        return last
    }

    fun snapshot(): TransportSnapshot = last
}
