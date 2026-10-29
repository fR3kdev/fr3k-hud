package com.mcpintelligence.fr3k.ui.settings

import org.junit.Assert.*
import org.junit.Test

class XiaozhiVoiceGateTest {
    @Test fun captureRequiresConsentPermissionAndReadySession() {
        assertFalse(XiaozhiVoiceGate.canTalk(false, true, VoicePhase.READY))
        assertFalse(XiaozhiVoiceGate.canTalk(true, false, VoicePhase.READY))
        assertFalse(XiaozhiVoiceGate.canTalk(true, true, VoicePhase.ACTIVATION_PENDING))
        assertTrue(XiaozhiVoiceGate.canTalk(true, true, VoicePhase.READY))
    }
}
