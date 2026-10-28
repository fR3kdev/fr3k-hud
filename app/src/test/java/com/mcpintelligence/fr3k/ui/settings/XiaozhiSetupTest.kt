package com.mcpintelligence.fr3k.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaozhiSetupTest {
    @Test fun configurationIsHonestAboutIdentityAndConsent() {
        assertEquals("https://voice.fr3k.dev/xiaozhi/ota/", XiaozhiSetup.endpoint)
        val text = XiaozhiSetup.instructions.joinToString(" ")
        assertTrue(text.contains(XiaozhiSetup.endpoint))
        assertTrue("virtual identity must not be presented as hardware", text.contains("virtual identity, not a hardware MAC address"))
        assertTrue("no background contact may be claimed", text.contains("only after you tick consent"))
        assertTrue(text.contains("Activation"))
        assertTrue("android voice stays the default", text.contains("Android system voice by default"))
        // The panel must not advertise verified enrollment while the contract is unverified.
        val lower = (XiaozhiSetup.status + " " + text).lowercase()
        assertFalse(lower.contains("verified"))
        assertFalse(lower.contains("working"))
    }
}
