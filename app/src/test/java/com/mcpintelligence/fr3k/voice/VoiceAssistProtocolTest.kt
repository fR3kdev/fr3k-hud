package com.mcpintelligence.fr3k.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAssistProtocolTest {

    @Test fun askCarriesTheTrimmedPrompt() {
        val request = VoiceAssistProtocol.decode(VoiceAssistProtocol.MSG_ASK, "  what is the pairing code?  ", null)
        assertEquals(VoiceAssistProtocol.Request.Ask("what is the pairing code?"), request)
    }

    @Test fun blankOrOversizedOrControlPromptsAreRejected() {
        assertEquals(VoiceAssistProtocol.Request.Invalid, VoiceAssistProtocol.decode(VoiceAssistProtocol.MSG_ASK, "   ", null))
        assertEquals(VoiceAssistProtocol.Request.Invalid, VoiceAssistProtocol.decode(VoiceAssistProtocol.MSG_ASK, null, null))
        val huge = "a".repeat(VoiceAssistProtocol.MAX_TEXT + 1)
        assertEquals(VoiceAssistProtocol.Request.Invalid, VoiceAssistProtocol.decode(VoiceAssistProtocol.MSG_ASK, huge, null))
        assertEquals(VoiceAssistProtocol.Request.Invalid, VoiceAssistProtocol.decode(VoiceAssistProtocol.MSG_ASK, "hello\u0000world", null))
    }

    @Test fun maximumLengthPromptIsAccepted() {
        val atLimit = "a".repeat(VoiceAssistProtocol.MAX_TEXT)
        assertTrue(VoiceAssistProtocol.decode(VoiceAssistProtocol.MSG_ASK, atLimit, null) is VoiceAssistProtocol.Request.Ask)
    }

    @Test fun speakAndStopAreDistinctFromAsk() {
        assertEquals(VoiceAssistProtocol.Request.Speak("step two"), VoiceAssistProtocol.decode(VoiceAssistProtocol.MSG_SPEAK, null, "step two"))
        assertEquals(VoiceAssistProtocol.Request.Stop, VoiceAssistProtocol.decode(VoiceAssistProtocol.MSG_STOP, null, null))
        // A speak request must never be treated as a prompt for the agent.
        assertEquals(VoiceAssistProtocol.Request.Invalid, VoiceAssistProtocol.decode(VoiceAssistProtocol.MSG_SPEAK, "ignored", null))
    }

    @Test fun unknownCodesAreInvalid() {
        assertEquals(VoiceAssistProtocol.Request.Invalid, VoiceAssistProtocol.decode(0, "x", "x"))
        assertEquals(VoiceAssistProtocol.Request.Invalid, VoiceAssistProtocol.decode(99, "x", "x"))
    }
}
