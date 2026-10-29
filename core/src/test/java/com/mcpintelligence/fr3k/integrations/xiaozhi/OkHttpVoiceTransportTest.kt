package com.mcpintelligence.fr3k.integrations.xiaozhi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class OkHttpVoiceTransportTest {
    private class Socket(private val request: Request) : WebSocket {
        val cancellations = AtomicInteger()
        val sent = mutableListOf<String>()
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent.add(text); return true }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancellations.incrementAndGet() }
    }

    private class Factory(private val block: Boolean) : WebSocket.Factory {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var socket: Socket
        lateinit var listener: WebSocketListener
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.listener = listener
            socket = Socket(request)
            entered.countDown()
            if (block) check(release.await(5, TimeUnit.SECONDS))
            return socket
        }
        fun open() {
            val response = Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1)
                .code(101).message("Switching Protocols").build()
            listener.onOpen(socket, response)
        }
    }

    private class Listener : VoiceTransport.Listener {
        var opens = 0
        override fun onOpen() { opens++ }
        override fun onText(text: String) = Unit
        override fun onFailure() = Unit
    }

    @Test fun closeBeforeFactoryReturnsCancelsSocketAndRejectsLateOpen() {
        val factory = Factory(block = true)
        val transport = OkHttpVoiceTransport(factory)
        val listener = Listener()
        val error = AtomicReference<Throwable>()
        val starter = Thread {
            try { transport.connect("wss://example.invalid/voice", emptyMap(), listener) }
            catch (t: Throwable) { error.set(t) }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(factory.entered.await(2, TimeUnit.SECONDS))
            transport.close()
            assertFalse(transport.send("before return"))
        } finally {
            factory.release.countDown()
            starter.join(5000)
        }
        assertFalse("connect thread did not finish", starter.isAlive)
        error.get()?.let { throw AssertionError("connect failed", it) }
        assertEquals(1, factory.socket.cancellations.get())
        assertFalse(transport.send("after return"))
        factory.open()
        assertEquals(2, factory.socket.cancellations.get())
        assertEquals(0, listener.opens)
        assertFalse(transport.send("after late open"))
    }

    @Test fun successfulOpenCanSendAndCloseCancelsSocket() {
        val factory = Factory(block = false)
        val transport = OkHttpVoiceTransport(factory)
        val listener = Listener()
        transport.connect("wss://example.invalid/voice", mapOf("Authorization" to "Bearer synthetic"), listener)
        assertEquals("Bearer synthetic", factory.socket.request().header("Authorization"))
        factory.open()
        assertEquals(1, listener.opens)
        assertTrue(transport.send("hello"))
        assertEquals(listOf("hello"), factory.socket.sent)
        transport.close()
        assertEquals(1, factory.socket.cancellations.get())
        assertFalse(transport.send("after close"))
    }
}
