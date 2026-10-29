package com.mcpintelligence.fr3k.integrations.voice

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring checks supplement the pure lifecycle/coroutine tests without needing a speech service. */
class AndroidVoiceLifecycleTest {
    private val source get() = File(System.getProperty("fr3k.appDir"),
        "src/main/java/com/mcpintelligence/fr3k/integrations/voice/AndroidVoice.kt").readText()

    @Test fun timeoutBoundsTheActualProviderSend() {
        assertTrue("The send itself must be bounded", source.contains("awaitVoiceReply(session.finalReplies, ANSWER_TIMEOUT_MS)"))
        assertFalse(source.contains("if (answer.isCompleted) answer.await() else null"))
    }

    @Test fun speechErrorsAreNotReportedAsSuccess() {
        assertFalse("TTS errors must not use success completion", source.contains("override fun onError(utteranceId: String?) { handler.post { finishSpeech() } }"))
        assertTrue(source.contains("finishSpeech(utteranceId, false)"))
    }

    @Test fun playbackRefreshesTuningAndWaitsForInitialization() {
        val playback = source.substringAfter("private fun speak(").substringBefore("private fun ensureTts")
        assertTrue("Existing engines must refresh saved rate and pitch", playback.contains("applyTuning"))
        assertTrue("First reply must wait for asynchronous engine init", playback.contains("pendingPlayback"))
    }

    @Test fun muteCancelsCaptureEvenAfterConversationError() {
        assertTrue("Mute needs a persistent microphone guard", source.contains("fun setMicrophoneMuted(muted: Boolean)"))
        val stop = source.substringAfter("fun stopConversation() {").substringBefore("/** Single push-to-talk")
        assertTrue("Stop must invalidate queued TTS", stop.contains("pendingPlayback = null"))
        assertTrue("Stop must resolve pending welcome as failure", stop.contains("result?.invoke(false)"))
    }

    @Test fun typedQuestionReplacesAnExistingSingleTurn() {
        val typed = source.substringAfter("fun askTyped(text: String) {").substringBefore("/** Press once")
        assertFalse(typed.contains("if (conversing) stopConversation()"))
        assertTrue(typed.contains("stopConversation()"))
    }

    @Test fun staleSpeechCallbacksCannotCompleteNewSpeech() {
        assertTrue("Completion must be utterance scoped", source.contains("completion.finish(utteranceId, success)"))
    }

    @Test fun queuedSpeechRemainsActiveUntilInitializationFinishes() {
        assertTrue(source.substringAfter("val isActive:").substringBefore("val inConversation")
            .contains("completion.activeId != null"))
    }

    @Test fun recognizerBusyRetryCannotResurrectAnOldTurn() {
        assertFalse(source.contains("handler.postDelayed({ if (conversing) listen() }, 600)"))
    }

    @Test fun startingConversationCancelsExistingTypedWork() {
        assertTrue(source.substringAfter("fun startConversation() {").substringBefore("fun stopConversation()")
            .contains("stopConversation()"))
    }

    @Test fun stoppingAlsoCancelsTypedTurns() {
        val body = source.substringAfter("fun stopConversation() {").substringBefore("/** Single push-to-talk")
        assertFalse("Typed turns must not bypass stop", body.contains("if (!conversing) return"))
    }
}
