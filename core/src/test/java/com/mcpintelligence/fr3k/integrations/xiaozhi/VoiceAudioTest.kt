package com.mcpintelligence.fr3k.integrations.xiaozhi

import org.junit.Assert.*
import org.junit.Test

class VoiceAudioTest {
    private class Fake : VoiceTransport {
        lateinit var listener: VoiceTransport.Listener
        val texts = mutableListOf<String>()
        val frames = mutableListOf<ByteArray>()
        override fun connect(url: String, headers: Map<String, String>, listener: VoiceTransport.Listener) { this.listener = listener }
        override fun send(text: String): Boolean { texts.add(text); return true }
        override fun sendBinary(bytes: ByteArray): Boolean { frames.add(bytes); return true }
        override fun close() = Unit
    }
    @Test fun versionTwoListenAndAudioFixture() {
        val t = Fake()
        val s = VoiceSession(Bootstrap.Result.Ready("wss://example.invalid", "test", 2), "device", "client", t)
        assertTrue(s.start()); t.listener.onOpen()
        t.listener.onText("""{"type":"hello","transport":"websocket","session_id":"synthetic"}""")
        assertTrue(s.startListening(VoiceSession.ListenMode.MANUAL))
        assertTrue(t.texts.last().contains("\"session_id\":\"synthetic\""))
        assertTrue(s.sendAudio(byteArrayOf(1, 2), 0x01020304))
        assertArrayEquals(byteArrayOf(0,2,0,0,0,0,0,0,1,2,3,4,0,0,0,2,1,2), t.frames.single())
        assertTrue(s.stopListening())
        assertFalse(s.sendAudio(byteArrayOf(1)))
        s.close()
    }
    @Test fun versionOneAndThreeAudioRoundTripAndBounds() {
        for (version in listOf(1, 3)) {
            val t = Fake(); val events = mutableListOf<VoiceSession.Event>()
            val s = VoiceSession(Bootstrap.Result.Ready("wss://example.invalid", "test", version), "device", "client", t, onEvent = { events.add(it) })
            assertFalse(s.sendAudio(byteArrayOf(1)))
            assertTrue(s.start()); t.listener.onOpen(); t.listener.onText("""{"type":"hello","transport":"websocket"}""")
            assertTrue(s.startListening(VoiceSession.ListenMode.AUTO))
            assertFalse(s.startListening(VoiceSession.ListenMode.AUTO))
            assertFalse(s.sendAudio(byteArrayOf()))
            assertFalse(s.sendAudio(ByteArray(65536)))
            assertTrue(s.sendAudio(byteArrayOf(5, 6)))
            val expected = if (version == 1) byteArrayOf(5, 6) else byteArrayOf(0, 0, 0, 2, 5, 6)
            assertArrayEquals(expected, t.frames.single())
            t.listener.onBinary(expected)
            assertArrayEquals(byteArrayOf(5, 6), (events.single() as VoiceSession.Event.Audio).payload)
            t.listener.onText("""{"type":"tts","state":"sentence_start","text":"hello"}""")
            assertEquals("hello", (events.last() as VoiceSession.Event.Tts).text)
            s.close(); t.listener.onBinary(expected); t.listener.onText("""{"type":"tts","state":"start"}""")
            assertEquals(2, events.size); assertFalse(s.stopListening())
        }
    }
    @Test fun malformedFramesFailClosed() {
        for (frame in listOf(byteArrayOf(0, 0, 0), byteArrayOf(0, 0, 0, 2, 1), ByteArray(65540))) {
            val t = Fake(); val events = mutableListOf<VoiceSession.Event>()
            val s = VoiceSession(Bootstrap.Result.Ready("wss://example.invalid", "test", 3), "device", "client", t, onEvent = { events.add(it) })
            s.start(); t.listener.onOpen(); t.listener.onText("""{"type":"hello","transport":"websocket"}""")
            t.listener.onBinary(frame)
            assertEquals(VoiceSession.State.FAILED, s.state); assertTrue(events.isEmpty())
        }
    }
    @Test fun versionTwoIncomingTimestampAndTruncatedHeader() {
        val t = Fake(); val events = mutableListOf<VoiceSession.Event>()
        val s = VoiceSession(Bootstrap.Result.Ready("wss://example.invalid", "test", 2), "device", "client", t, onEvent = { events.add(it) })
        s.start(); t.listener.onOpen(); t.listener.onText("""{"type":"hello","transport":"websocket"}""")
        t.listener.onBinary(byteArrayOf(0,2,0,0,0,0,0,0,1,2,3,4,0,0,0,2,1,2))
        val audio = events.single() as VoiceSession.Event.Audio
        assertEquals(0x01020304L, audio.timestamp); assertArrayEquals(byteArrayOf(1, 2), audio.payload)
        t.listener.onBinary(ByteArray(15)); assertEquals(VoiceSession.State.FAILED, s.state)
    }
}
