package com.mcpintelligence.fr3k.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceSpeechCompletionTest {
    @Test fun onlyMatchingEngineCompletionFinishesSpeech() {
        val speech = VoiceAssistProtocol.SpeechCompletion()
        speech.begin("a")
        assertFalse(speech.finish("old"))
        assertTrue(speech.finish("a"))
        assertFalse(speech.finish("a"))
    }
    @Test fun cancellationInvalidatesLateCompletion() {
        val speech = VoiceAssistProtocol.SpeechCompletion()
        speech.begin("a"); speech.cancel()
        assertFalse(speech.finish("a"))
    }
}
