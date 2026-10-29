package com.mcpintelligence.fr3k.integrations.voice

import org.junit.Assert.*
import org.junit.Test

class AndroidVoiceSpeechTest {
    @Test fun errorIsFailureNotWelcomeSuccess() {
        val completion = VoiceSpeechCompletion()
        val results = mutableListOf<Boolean>()
        completion.begin("welcome", results::add)
        assertTrue(completion.finish("welcome", false))
        assertEquals(listOf(false), results)
    }

    @Test fun staleOrDuplicateCallbackCannotCompleteNewUtterance() {
        val completion = VoiceSpeechCompletion()
        val results = mutableListOf<Boolean>()
        completion.begin("new", results::add)
        assertFalse(completion.finish("old", true))
        assertFalse(completion.finish(null, true))
        assertTrue(results.isEmpty())
        assertTrue(completion.finish("new", true))
        assertFalse(completion.finish("new", false))
        assertEquals(listOf(true), results)
    }

    @Test fun cancellingDropsCompletionBeforeLateDone() {
        val completion = VoiceSpeechCompletion()
        val results = mutableListOf<Boolean>()
        completion.begin("stopped", results::add)
        completion.clear()
        assertFalse(completion.finish("stopped", true))
        assertTrue(results.isEmpty())
    }

    @Test fun completionIsClearedBeforeReentrantCallback() {
        val completion = VoiceSpeechCompletion()
        val results = mutableListOf<Boolean>()
        completion.begin("first") { completion.begin("second", results::add) }
        completion.finish("first", true)
        assertTrue(completion.finish("second", false))
        assertEquals(listOf(false), results)
    }
}
