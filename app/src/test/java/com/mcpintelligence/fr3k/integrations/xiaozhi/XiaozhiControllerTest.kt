package com.mcpintelligence.fr3k.integrations.xiaozhi

import org.junit.Assert.*
import org.junit.Test

class XiaozhiControllerTest {
    @Test fun activationRetryConnectsOnlyAfterHello() {
        val id = XiaozhiController.Identity("02:00:00:00:00:01", "123e4567-e89b-42d3-a456-426614174000")
        var count = 0
        val listener = java.util.concurrent.atomic.AtomicReference<VoiceTransport.Listener>()
        val transport = object : VoiceTransport {
            override fun connect(url: String, headers: Map<String, String>, listenerArg: VoiceTransport.Listener) { listener.set(listenerArg) }
            override fun send(text: String) = true
            override fun close() {}
        }
        val controller = XiaozhiController(identity = { id }, discover = { _, _, _ ->
            if (count++ == 0) Bootstrap.Result.ActivationCode("123456") else Bootstrap.Result.Ready("wss://example.invalid/", "token", 1)
        }, makeSession = { config, identity, events -> VoiceSession(config, identity.deviceId, identity.clientId, transport, onEvent = events) })
        fun await(status: XiaozhiController.Status) {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (controller.status != status && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(status, controller.status)
        }
        controller.discover(true); await(XiaozhiController.Status.ACTIVATION_REQUIRED)
        assertEquals("123456", controller.activationCode)
        controller.discover(true); await(XiaozhiController.Status.CONNECTING)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (listener.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
        assertNotNull(listener.get())
        listener.get().onOpen()
        listener.get().onText("""{"type":"hello","transport":"websocket"}""")
        await(XiaozhiController.Status.READY)
        controller.close()
        assertEquals(XiaozhiController.Status.CLOSED, controller.status)
    }
    @Test fun refusedHandshakeBecomesActivationRequiredWithServerText() {
        val id = XiaozhiController.Identity("02:00:00:00:00:01", "123e4567-e89b-42d3-a456-426614174000")
        val listener = java.util.concurrent.atomic.AtomicReference<VoiceTransport.Listener>()
        val transport = object : VoiceTransport {
            override fun connect(url: String, headers: Map<String, String>, listenerArg: VoiceTransport.Listener) { listener.set(listenerArg) }
            override fun send(text: String) = true
            override fun close() {}
        }
        val controller = XiaozhiController(identity = { id }, discover = { _, _, _ ->
            Bootstrap.Result.Ready("wss://example.invalid/", "token", 1)
        }, makeSession = { config, identity, events -> VoiceSession(config, identity.deviceId, identity.clientId, transport, onEvent = events) })
        controller.discover(true)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (listener.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
        listener.get().onOpen()
        listener.get().onText("""{"type":"stt","text":"bind this device with code 508752","session_id":"s-1"}""")
        val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (controller.status != XiaozhiController.Status.ACTIVATION_REQUIRED && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(XiaozhiController.Status.ACTIVATION_REQUIRED, controller.status)
        assertEquals("bind this device with code 508752", controller.serverNotice)
        controller.close()
    }
    @Test fun discoveryRequiresExplicitConsent() {
        var calls = 0
        val controller = XiaozhiController(identity = { XiaozhiController.Identity("02:00:00:00:00:01", "123e4567-e89b-42d3-a456-426614174000") }, discover = { _, _, _ -> calls++; Bootstrap.Result.Invalid })
        assertEquals(XiaozhiController.Status.IDLE, controller.status)
        assertEquals(0, calls)
        controller.discover(false)
        assertEquals(0, calls)
        controller.discover(true)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (controller.status != XiaozhiController.Status.ERROR && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(1, calls)
        assertEquals(XiaozhiController.Status.ERROR, controller.status)
        controller.close()
    }
}
