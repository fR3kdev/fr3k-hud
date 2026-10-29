package com.mcpintelligence.fr3k.integrations.voice

import org.junit.Assert.*
import org.junit.Test

class AndroidVoiceTest {
    @Test fun permissionDeniedDoesNotStartRecognition() {
        val gate = VoiceTurnGate()
        assertEquals(VoiceTurnGate.Start.PermissionRequired, gate.start(false, false))
        assertFalse(gate.active)
    }
}
