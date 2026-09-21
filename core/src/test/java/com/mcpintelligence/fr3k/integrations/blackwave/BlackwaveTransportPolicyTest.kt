package com.mcpintelligence.fr3k.integrations.blackwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackwaveTransportPolicyTest {
    @Test fun wirelessIsPreferredEvenWhenUsbIsReady() {
        val policy = BlackwaveTransportPolicy()
        val s = policy.observe(WirelessHealth.HEALTHY, usbReady = true)
        assertEquals(BlackwaveLinkState.WIRELESS_ACTIVE, s.state)
        assertEquals("wireless", s.activeTransport)
    }

    @Test fun transientWirelessLossDoesNotImmediatelyFailOver() {
        val policy = BlackwaveTransportPolicy(failureThreshold = 2)
        val s = policy.observe(WirelessHealth.UNREACHABLE, usbReady = true)
        assertEquals(BlackwaveLinkState.WIRELESS_DEGRADED, s.state)
        assertEquals("wireless", s.activeTransport)
    }

    @Test fun sustainedReachabilityLossUsesAuthenticatedUsbPeer() {
        val policy = BlackwaveTransportPolicy(failureThreshold = 2)
        policy.observe(WirelessHealth.UNREACHABLE, usbReady = true)
        val s = policy.observe(WirelessHealth.UNREACHABLE, usbReady = true)
        assertEquals(BlackwaveLinkState.USB_FALLBACK, s.state)
        assertEquals("usb", s.activeTransport)
    }
    @Test fun noUsbPeerMeansOfflineNotFakeFallback() {
        val policy = BlackwaveTransportPolicy(failureThreshold = 1)
        val s = policy.observe(WirelessHealth.UNREACHABLE, usbReady = false)
        assertEquals(BlackwaveLinkState.OFFLINE, s.state)
        assertEquals("none", s.activeTransport)
    }

    @Test fun authFailureNeverFallsBackToUsb() {
        val policy = BlackwaveTransportPolicy(failureThreshold = 1)
        val s = policy.observe(WirelessHealth.AUTH_FAILED, usbReady = true)
        assertEquals(BlackwaveLinkState.AUTH_FAILED, s.state)
        assertEquals("none", s.activeTransport)
        assertTrue(s.reason.contains("bypass forbidden"))
    }

    @Test fun trustFailureNeverFallsBackToUsb() {
        val policy = BlackwaveTransportPolicy(failureThreshold = 1)
        val s = policy.observe(WirelessHealth.TRUST_FAILED, usbReady = true)
        assertEquals(BlackwaveLinkState.TRUST_FAILED, s.state)
        assertEquals("none", s.activeTransport)
    }

    @Test fun remoteServiceFailureNeverFallsBackToUsb() {
        val policy = BlackwaveTransportPolicy(failureThreshold = 1)
        val s = policy.observe(WirelessHealth.REMOTE_FAILED, usbReady = true)
        assertEquals(BlackwaveLinkState.REMOTE_FAILED, s.state)
        assertEquals("none", s.activeTransport)
    }
    @Test fun recoveredWirelessImmediatelyTakesBackTrafficWithCableAttached() {
        val policy = BlackwaveTransportPolicy(failureThreshold = 1, recoveryThreshold = 1)
        val usb = policy.observe(WirelessHealth.UNREACHABLE, usbReady = true)
        assertEquals(BlackwaveLinkState.USB_FALLBACK, usb.state)

        val recovered = policy.observe(WirelessHealth.HEALTHY, usbReady = true)
        assertEquals(BlackwaveLinkState.WIRELESS_ACTIVE, recovered.state)
        assertEquals("wireless", recovered.activeTransport)
        assertTrue(recovered.usbReady)
        assertTrue(recovered.reason.contains("restored"))
    }

    @Test fun optionalRecoveryHysteresisCanBeEnabled() {
        val policy = BlackwaveTransportPolicy(failureThreshold = 1, recoveryThreshold = 2)
        policy.observe(WirelessHealth.UNREACHABLE, usbReady = true)
        val first = policy.observe(WirelessHealth.HEALTHY, usbReady = true)
        assertEquals(BlackwaveLinkState.WIRELESS_RECOVERING, first.state)
        assertEquals("usb", first.activeTransport)
        val second = policy.observe(WirelessHealth.HEALTHY, usbReady = true)
        assertEquals(BlackwaveLinkState.WIRELESS_ACTIVE, second.state)
    }
}
