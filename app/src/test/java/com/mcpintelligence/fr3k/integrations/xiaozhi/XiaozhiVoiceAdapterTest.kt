package com.mcpintelligence.fr3k.integrations.xiaozhi

import com.mcpintelligence.fr3k.ui.settings.VoicePhase
import org.junit.Assert.*
import org.junit.Test

class XiaozhiVoiceAdapterTest {

    private class FakeController : XiaozhiControllerLink {
        override var status = XiaozhiController.Status.IDLE
        override var activationCode: String? = null
        override var sessionState = VoiceSession.State.IDLE
        override var downlinkSampleRate = 24000
        var serverNoticeOverride: String? = null
        override val serverNotice: String? get() = serverNoticeOverride
        var discoverCalls = 0
        var consentedArgs = mutableListOf<Boolean>()
        var listening = false
        var stopListenCalls = 0
        var closed = false
        var lastSent: ByteArray? = null
        lateinit var onState: (XiaozhiController.Status, String?) -> Unit
        lateinit var onEvent: (VoiceSession.Event) -> Unit
        override fun discover(consented: Boolean) {
            discoverCalls++
            consentedArgs.add(consented)
            if (!consented) return
        }
        override fun startListening(): Boolean { listening = true; return true }
        override fun stopListening(): Boolean { listening = false; stopListenCalls++; return true }
        override fun sendAudio(packet: ByteArray): Boolean { lastSent = packet; return true }
        override fun close() { closed = true }
        fun emit(status: XiaozhiController.Status, code: String? = null) {
            this.status = status
            if (code != null) activationCode = code
            onState(status, code)
        }
    }

    private class FakeAudio : XiaozhiAudioLink {
        var rate = 0
        var playing = false
        var capturing = false
        var startCaptureThrows: Exception? = null
        var closed = false
        val enqueued = mutableListOf<ByteArray>()
        var sender: ((ByteArray) -> Boolean)? = null
        var onError: ((Throwable) -> Unit)? = null
        override fun startPlayback() { playing = true }
        override fun startCapture() { startCaptureThrows?.let { throw it }; capturing = true }
        override fun stopCapture() { capturing = false }
        override fun enqueue(packet: ByteArray) { enqueued.add(packet) }
        override fun close() { closed = true; playing = false; capturing = false }
        /** The harness hands out one instance; a reconnect is expected to build a fresh engine. */
        fun reset() { closed = false; playing = false; capturing = false; enqueued.clear(); startCaptureThrows = null }
    }

    private class FakeSpeaker : XiaozhiSpeaker {
        val spoken = mutableListOf<String>()
        var stops = 0
        var shut = false
        override fun speak(text: String) { spoken.add(text) }
        override fun stop() { stops++ }
        override fun shutdown() { shut = true }
    }

    private class Harness {
        val controller = FakeController()
        val audio = FakeAudio()
        val speaker = FakeSpeaker()
        val adapter = XiaozhiVoiceAdapterImpl(
            makeController = { onState, onEvent ->
                controller.also { it.onState = onState; it.onEvent = onEvent }
            },
            makeAudio = { rate, send, onError ->
                audio.also { it.rate = rate; it.sender = send; it.onError = onError; it.reset() }
            },
            speaker = speaker,
            isMainThread = { true },
            post = { it() },
        )
        fun ready() {
            adapter.connect()
            controller.downlinkSampleRate = 16000
            controller.sessionState = VoiceSession.State.READY
            controller.emit(XiaozhiController.Status.READY)
        }
    }

    @Test fun nothingIsContactedUntilTheOperatorActs() {
        val h = Harness()
        assertEquals(0, h.controller.discoverCalls)
        assertEquals(VoicePhase.IDLE, h.adapter.status.value.phase)
        h.adapter.connect()
        assertEquals(1, h.controller.discoverCalls)
        assertEquals(listOf(true), h.controller.consentedArgs)
    }

    @Test fun activationCodeIsShownAndRetryIsPossible() {
        val h = Harness()
        h.adapter.connect()
        h.controller.emit(XiaozhiController.Status.ACTIVATION_REQUIRED, "654321")
        assertEquals(VoicePhase.ACTIVATION_PENDING, h.adapter.status.value.phase)
        assertEquals("654321", h.adapter.status.value.activationCode)
        h.adapter.connect()
        assertEquals(2, h.controller.discoverCalls)
    }

    @Test fun serverRefusalTextIsShownVerbatimInsteadOfAGenericError() {
        val h = Harness()
        h.adapter.connect()
        h.controller.serverNoticeOverride = "bind this device with code 508752"
        h.controller.onEvent(VoiceSession.Event.ServerNotice("bind this device with code 508752"))
        assertEquals(VoicePhase.ACTIVATION_PENDING, h.adapter.status.value.phase)
        assertEquals("bind this device with code 508752", h.adapter.status.value.message)
        h.controller.emit(XiaozhiController.Status.ACTIVATION_REQUIRED)
        assertEquals("bind this device with code 508752", h.adapter.status.value.message)
    }

    @Test fun readyOpensPlaybackAtTheNegotiatedRate() {
        val h = Harness()
        h.ready()
        assertEquals(VoicePhase.READY, h.adapter.status.value.phase)
        assertTrue(h.audio.playing)
        assertEquals(16000, h.audio.rate)
    }

    @Test fun pushToTalkStopsCaptureButKeepsReplyPlayback() {
        val h = Harness()
        h.ready()
        h.adapter.startTalking()
        assertEquals(VoicePhase.LISTENING, h.adapter.status.value.phase)
        assertTrue(h.controller.listening)
        assertTrue(h.audio.capturing)
        h.adapter.stopTalking()
        assertEquals(VoicePhase.READY, h.adapter.status.value.phase)
        assertFalse(h.audio.capturing)
        assertFalse("playback must survive button release", h.audio.closed)
        assertTrue(h.audio.playing)
        assertEquals(1, h.controller.stopListenCalls)
    }

    @Test fun microphoneFailureReportsAndDoesNotLeaveTheTurnOpen() {
        val h = Harness()
        h.ready()
        h.audio.startCaptureThrows = IllegalStateException("Microphone permission required")
        h.adapter.startTalking()
        assertEquals(VoicePhase.ERROR, h.adapter.status.value.phase)
        assertFalse(h.controller.listening)
    }

    @Test fun defaultOutputSpeaksTextAndNeverPlaysServerAudio() {
        val h = Harness()
        h.ready()
        assertTrue(h.adapter.speaksWithAndroidTts)
        h.controller.onEvent(VoiceSession.Event.Tts("sentence_start", "hello there"))
        assertEquals(listOf("hello there"), h.speaker.spoken)
        h.controller.onEvent(VoiceSession.Event.Audio(byteArrayOf(1, 2, 3), 0))
        assertTrue("server audio must stay silent in android voice mode", h.audio.enqueued.isEmpty())
    }

    @Test fun xiaozhiOutputPlaysServerAudioAndStopsAndroidSpeech() {
        val h = Harness()
        h.ready()
        h.adapter.setAndroidTtsOutput(false)
        assertFalse(h.adapter.speaksWithAndroidTts)
        h.controller.onEvent(VoiceSession.Event.Tts("sentence_start", "hello there"))
        assertTrue(h.speaker.spoken.isEmpty())
        h.controller.onEvent(VoiceSession.Event.Audio(byteArrayOf(1, 2, 3), 0))
        assertEquals(1, h.audio.enqueued.size)
    }

    @Test fun captureFramesAreSentOverTheSession() {
        val h = Harness()
        h.ready()
        assertEquals(true, h.audio.sender?.invoke(byteArrayOf(9)))
        assertArrayEquals(byteArrayOf(9), h.controller.lastSent)
    }

    @Test fun disconnectKeepsTheAdapterReusable() {
        val h = Harness()
        h.ready()
        h.adapter.disconnect()
        assertTrue(h.audio.closed)
        assertEquals(VoicePhase.IDLE, h.adapter.status.value.phase)
        h.adapter.connect()
        assertEquals(2, h.controller.discoverCalls)
        h.controller.emit(XiaozhiController.Status.READY)
        assertEquals(VoicePhase.READY, h.adapter.status.value.phase)
        assertFalse("a reconnect must build a fresh audio engine", h.audio.closed)
    }

    @Test fun closeReleasesEverythingAndIsIdempotent() {
        val h = Harness()
        h.ready()
        h.adapter.close()
        assertTrue(h.audio.closed)
        assertTrue(h.controller.closed)
        assertTrue(h.speaker.shut)
        h.adapter.close()
        assertEquals(1, h.controller.discoverCalls)
    }
}
