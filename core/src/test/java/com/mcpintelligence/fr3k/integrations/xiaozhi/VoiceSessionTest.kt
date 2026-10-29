package com.mcpintelligence.fr3k.integrations.xiaozhi

import org.junit.Assert.*
import org.junit.Test

class VoiceSessionTest {
    @Test fun bootstrapBodyIdentifiesAppAndVirtualDeviceWithoutClaimingHardware() {
        val body = kotlinx.serialization.json.Json.parseToJsonElement(BootstrapClient.androidSystemInfo("02:00:00:00:00:01", "FR3K HUD", "0.4.16")) as kotlinx.serialization.json.JsonObject
        assertEquals("02:00:00:00:00:01", (body["mac_address"] as kotlinx.serialization.json.JsonPrimitive).content)
        val app = body["application"] as kotlinx.serialization.json.JsonObject
        assertEquals("FR3K HUD", (app["name"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("0.4.16", (app["version"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertFalse(body.containsKey("board")); assertFalse(body.containsKey("chip_model_name"))
    }
    @Test fun serverHelloNegotiatesDownlinkRate() {
        val transport = Fake(); val session = VoiceSession(config(), "device", "client", transport)
        assertTrue(session.start()); transport.listener.onOpen()
        transport.listener.onText("""{"type":"hello","transport":"websocket","audio_params":{"format":"opus","sample_rate":24000,"channels":1}}""")
        assertEquals(VoiceSession.State.READY, session.state)
        assertEquals(24000, session.downlinkSampleRate)
    }
    @Test fun refusedHandshakeSurfacesServerInstructionVerbatim() {
        val transport = Fake()
        val events = mutableListOf<VoiceSession.Event>()
        val session = VoiceSession(config(), "device", "client", transport, onEvent = { events.add(it) })
        assertTrue(session.start()); transport.listener.onOpen()
        // Observed live from voice.fr3k.dev for an unbound device.
        transport.listener.onText("""{"type":"stt","text":"bind this device with code 508752","session_id":"s-1"}""")
        assertEquals(VoiceSession.State.FAILED, session.state)
        assertEquals("bind this device with code 508752", (events.single() as VoiceSession.Event.ServerNotice).text)
        assertTrue(transport.closed)
    }
    @Test fun protocolFaultWithoutTextEmitsNoNotice() {
        val transport = Fake()
        val events = mutableListOf<VoiceSession.Event>()
        val session = VoiceSession(config(), "device", "client", transport, onEvent = { events.add(it) })
        assertTrue(session.start()); transport.listener.onOpen()
        transport.listener.onText("""{"type":"hello","transport":"websocket","audio_params":{"format":"pcm"}}""")
        assertEquals(VoiceSession.State.FAILED, session.state)
        assertTrue(events.isEmpty())
    }
    private class Fake : VoiceTransport {
        lateinit var listener: VoiceTransport.Listener
        var headers: Map<String, String> = emptyMap()
        val sent = mutableListOf<String>()
        var closed = false
        override fun connect(url: String, headers: Map<String, String>, listener: VoiceTransport.Listener) {
            this.headers = headers; this.listener = listener
        }
        override fun send(text: String): Boolean { sent.add(text); return true }
        override fun close() { closed = true }
    }
    private fun config() = Bootstrap.parse("""{"websocket":{"url":"wss://example.invalid/voice","token":"synthetic-secret","version":1}}""") as Bootstrap.Result.Ready

    private class Clock : VoiceDeadlineScheduler {
        lateinit var action: () -> Unit
        var cancelled = false
        var delay = 0L
        override fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
            delay = delayMillis; this.action = action
            return { cancelled = true }
        }
    }

    @Test fun staleOkHttpOpenAfterCloseCannotRestoreSocket() {
        val transport = OkHttpVoiceTransport()
        val callback = object : VoiceTransport.Listener {
            override fun onOpen() = Unit
            override fun onText(text: String) = Unit
            override fun onFailure() = Unit
        }
        val listenerClass = Class.forName("com.mcpintelligence.fr3k.integrations.xiaozhi.OkHttpVoiceTransport\$connect\$created\$1")
        val constructor = listenerClass.declaredConstructors.single().apply { isAccessible = true }
        val listener = constructor.newInstance(transport, callback) as okhttp3.WebSocketListener
        val request = okhttp3.Request.Builder().url("https://example.invalid/").build()
        val socket = object : okhttp3.WebSocket {
            override fun request() = request
            override fun queueSize() = 0L
            override fun send(text: String) = true
            override fun send(bytes: okio.ByteString) = true
            override fun close(code: Int, reason: String?) = true
            override fun cancel() = Unit
        }
        val response = okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(101).message("Switching Protocols").build()
        transport.close()
        listener.onOpen(socket, response)
        assertFalse("closed transport must not send on stale socket", transport.send("hello"))
    }

    @Test fun timeoutClosesWhileConnectIsBlocked() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val transport = object : VoiceTransport {
            override fun connect(url: String, headers: Map<String, String>, listener: VoiceTransport.Listener) {
                entered.countDown(); release.await()
            }
            override fun send(text: String) = true
            override fun close() { closed.countDown() }
        }
        val clock = Clock(); val session = VoiceSession(config(), "device", "client", transport, clock)
        val starter = Thread { session.start() }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val timer = Thread { clock.action() }.apply { isDaemon = true; start() }
            assertTrue("deadline must close before connect returns", closed.await(1, java.util.concurrent.TimeUnit.SECONDS))
            timer.join(1000)
            assertEquals(VoiceSession.State.FAILED, session.state)
        } finally { release.countDown(); starter.join(2000) }
    }

    @Test fun closeCompletesWhileConnectIsBlocked() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val transport = object : VoiceTransport {
            override fun connect(url: String, headers: Map<String, String>, listener: VoiceTransport.Listener) {
                entered.countDown(); release.await(); listener.onOpen()
            }
            override fun send(text: String) = throw AssertionError("late open sent hello")
            override fun close() { closed.countDown() }
        }
        val session = VoiceSession(config(), "device", "client", transport, Clock())
        val starter = Thread { session.start() }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val closer = Thread { session.close() }.apply { isDaemon = true; start() }
            assertTrue(closed.await(1, java.util.concurrent.TimeUnit.SECONDS))
            closer.join(1000)
            assertEquals(VoiceSession.State.CLOSED, session.state)
        } finally { release.countDown(); starter.join(2000) }
        assertEquals(VoiceSession.State.CLOSED, session.state)
    }

    @Test fun stalledConnectionEventuallyFailsAndCloses() {
        val clock = Clock(); val transport = Fake()
        val session = VoiceSession(config(), "device", "client", transport, clock, 2000)
        assertTrue(session.start())
        assertEquals(2000L, clock.delay)
        clock.action()
        assertEquals(VoiceSession.State.FAILED, session.state)
        assertTrue(transport.closed)
    }

    @Test fun stalledHelloFailsAndLateCallbacksCannotReviveSession() {
        val clock = Clock(); val transport = Fake()
        val session = VoiceSession(config(), "device", "client", transport, clock)
        assertTrue(session.start()); transport.listener.onOpen(); clock.action()
        assertEquals(VoiceSession.State.FAILED, session.state)
        assertTrue(transport.closed); assertTrue(clock.cancelled)
        transport.listener.onOpen()
        transport.listener.onText("""{"type":"hello","transport":"websocket"}""")
        assertEquals(VoiceSession.State.FAILED, session.state)
    }

    @Test fun successfulHelloCancelsDeadlineAndLateDeadlineIsHarmless() {
        val clock = Clock(); val transport = Fake()
        val session = VoiceSession(config(), "device", "client", transport, clock)
        assertTrue(session.start()); transport.listener.onOpen()
        transport.listener.onText("""{"type":"hello","transport":"websocket"}""")
        assertTrue(clock.cancelled); clock.action()
        assertEquals(VoiceSession.State.READY, session.state)
        assertFalse(transport.closed)
    }

    @Test fun closeCancelsDeadlineAndLateCallbacksCannotReviveSession() {
        val clock = Clock(); val transport = Fake()
        val session = VoiceSession(config(), "device", "client", transport, clock)
        assertTrue(session.start()); session.close()
        assertTrue(clock.cancelled); assertTrue(transport.closed)
        clock.action(); transport.listener.onOpen(); transport.listener.onFailure()
        transport.listener.onText("""{"type":"hello","transport":"websocket"}""")
        assertEquals(VoiceSession.State.CLOSED, session.state)
    }

    @Test fun synchronousTransportFailureMakesStartFail() {
        val transport = object : VoiceTransport {
            override fun connect(url: String, headers: Map<String, String>, listener: VoiceTransport.Listener) { listener.onFailure() }
            override fun send(text: String) = true
            override fun close() = Unit
        }
        val session = VoiceSession(config(), "device", "client", transport, Clock())
        assertFalse(session.start())
        assertEquals(VoiceSession.State.FAILED, session.state)
    }

    @Test fun rejectsInvalidHelloAndVersionWithoutExposingPayload() {
        val messages = listOf("not json secret", "[]", "{}", """{"type":"hello","transport":"mqtt"}""", """{"type":"hello","transport":"websocket","version":2}""", """{"type":"hello","transport":"websocket","version":"1"}""", """{"type":"hello","transport":"websocket","session_id":4}""", "x".repeat(65537))
        messages.forEach { message ->
            val transport = Fake(); val session = VoiceSession(config(), "device", "client", transport)
            assertTrue(session.start()); transport.listener.onOpen(); transport.listener.onText(message)
            assertEquals(VoiceSession.State.FAILED, session.state); assertTrue(transport.closed)
            assertFalse(session.toString().contains("secret"))
        }
    }

    @Test fun refusesMissingCredentialsAndUnsafeConfigurationBeforeTransport() {
        val candidates = listOf(Bootstrap.Result.Ready("ws://example.invalid", "secret", 1), Bootstrap.Result.Ready("wss://user:pass@example.invalid", "secret", 1), Bootstrap.Result.Ready("wss://example.invalid", null, 1), Bootstrap.Result.Ready("wss://example.invalid", "bad\nheader", 1), Bootstrap.Result.Ready("wss://example.invalid", "secret", 4))
        candidates.forEach { val transport = Fake(); assertFalse(VoiceSession(it, "device", "client", transport).start()); assertTrue(transport.headers.isEmpty()) }
        val transport = Fake(); assertFalse(VoiceSession(config(), "bad\nidentity", "client", transport).start()); assertTrue(transport.headers.isEmpty())
    }

    @Test fun transportFailureIsRedactedAndClosesSession() {
        val transport = Fake(); val session = VoiceSession(config(), "device", "client", transport)
        assertTrue(session.start()); transport.listener.onFailure()
        assertEquals(VoiceSession.State.FAILED, session.state); assertTrue(transport.closed)
    }

    @Test fun serverHelloMayOmitVersionAsInFirmwareProtocol() {
        val transport = Fake(); val session = VoiceSession(config(), "device", "client", transport)
        assertTrue(session.start()); transport.listener.onOpen()
        transport.listener.onText("""{"type":"hello","transport":"websocket"}""")
        assertEquals(VoiceSession.State.READY, session.state)
        session.close(); assertEquals(VoiceSession.State.CLOSED, session.state)
    }

    @Test fun authenticatedHelloAndServerHelloEstablishSession() {
        val transport = Fake()
        val session = VoiceSession(config(), "synthetic-device", "synthetic-client", transport)
        assertTrue(session.start())
        assertEquals("Bearer synthetic-secret", transport.headers["Authorization"])
        assertEquals("1", transport.headers["Protocol-Version"])
        transport.listener.onOpen()
        assertTrue(transport.sent.single().contains("\"type\":\"hello\""))
        transport.listener.onText("""{"type":"hello","transport":"websocket","version":1,"session_id":"test-session"}""")
        assertEquals(VoiceSession.State.READY, session.state)
        assertFalse(session.toString().contains("synthetic-secret"))
    }
}
