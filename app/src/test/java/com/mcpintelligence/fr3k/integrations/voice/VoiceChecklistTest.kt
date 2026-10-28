package com.mcpintelligence.fr3k.integrations.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceChecklistTest {

    private val ready = VoiceChecklist.Device(microphone = true, tts = true, recogniser = true, provider = true)

    @Test fun everythingPresentMeansNoWalkthrough() {
        assertTrue(VoiceChecklist.ready(ready))
        assertTrue(VoiceChecklist.missing(ready).isEmpty())
        assertNull(VoiceChecklist.walkthrough(ready))
    }

    @Test fun eachMissingPieceIsItsOwnStep() {
        val device = VoiceChecklist.Device(microphone = false, tts = false, recogniser = false, provider = false)
        val steps = VoiceChecklist.missing(device)
        assertEquals(4, steps.size)
        assertEquals(
            listOf(
                VoiceChecklist.Fix.MICROPHONE,
                VoiceChecklist.Fix.TTS_SETTINGS,
                VoiceChecklist.Fix.SPEECH_INPUT,
                VoiceChecklist.Fix.PROVIDER,
            ),
            steps.map { it.fix },
        )
    }

    @Test fun microphoneIsFixedFirst() {
        val device = ready.copy(microphone = false, provider = false)
        val steps = VoiceChecklist.missing(device)
        assertEquals(VoiceChecklist.Fix.MICROPHONE, steps.first().fix)
        assertEquals(VoiceChecklist.Fix.PROVIDER, steps.last().fix)
    }

    @Test fun walkthroughMentionsEveryMissingPieceAndStaysSpeakable() {
        val device = VoiceChecklist.Device(microphone = false, tts = false, recogniser = false, provider = true)
        val spoken = VoiceChecklist.walkthrough(device)!!
        assertTrue(spoken.startsWith("First"))
        assertTrue(spoken.contains("microphone"))
        assertTrue(spoken.contains("text to speech"))
        assertTrue(spoken.contains("speech recognition"))
        assertFalse("a configured provider must not be mentioned as missing", spoken.contains("model provider"))
        // Spoken text must survive a TTS engine: no control characters, reasonable length.
        assertTrue(spoken.none { it.isISOControl() })
        assertTrue(spoken.length < 600)
    }

    @Test fun missingProviderIsCalledOutOnItsOwn() {
        val device = ready.copy(provider = false)
        val steps = VoiceChecklist.missing(device)
        assertEquals(1, steps.size)
        assertEquals(VoiceChecklist.Fix.PROVIDER, steps.single().fix)
    }
}
