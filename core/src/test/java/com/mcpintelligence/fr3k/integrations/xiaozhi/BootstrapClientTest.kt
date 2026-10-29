package com.mcpintelligence.fr3k.integrations.xiaozhi

import org.junit.Assert.*
import org.junit.Test

class BootstrapClientTest {
    @Test fun postsExplicitConfigurationAndParsesResponse() {
        var captured = false
        val client = BootstrapClient { url, headers, body ->
            captured = url == "https://example.invalid/bootstrap" && headers["Device-Id"] == "device" && headers["Client-Id"] == "client" && body == "{}"
            BootstrapClient.Response(200, """{"websocket":{"url":"wss://example.invalid/voice"}}""")
        }
        val result = client.discover("https://example.invalid/bootstrap", "device", "client", "{}")
        assertTrue(captured)
        assertTrue(result is Bootstrap.Result.Ready)
    }

    @Test fun insecureAndCredentialBearingEndpointsNeverCallTransport() {
        var calls = 0
        val client = BootstrapClient { _, _, _ -> calls++; BootstrapClient.Response(200, "{}") }
        listOf("http://localhost/bootstrap", "https://u:secret@example.invalid/", "https://example.invalid/#fragment", "not a url").forEach {
            assertSame(Bootstrap.Result.Invalid, client.discover(it, "device", "client", "{}"))
        }
        assertEquals(0, calls)
    }

    @Test fun malformedAndFailedResponsesAreRedacted() {
        listOf(BootstrapClient.Response(200, "not json secret"), BootstrapClient.Response(500, "secret"), BootstrapClient.Response(200, "x".repeat(65537))).forEach { response ->
            val result = BootstrapClient { _, _, _ -> response }.discover("https://example.invalid/", "device", "client", "{}")
            assertSame(Bootstrap.Result.Invalid, result)
            assertFalse(result.toString().contains("secret"))
        }
        val result = BootstrapClient { _, _, _ -> throw java.io.IOException("secret") }.discover("https://example.invalid/", "device", "client", "{}")
        assertSame(Bootstrap.Result.Invalid, result)
    }

    @Test fun activationPendingTakesPrecedenceOverReadyAndRedactsSecrets() {
        val client = BootstrapClient { _, _, _ -> BootstrapClient.Response(200, """{"activation":{"code":"secret-code","challenge":"secret-challenge"},"websocket":{"url":"wss://example.invalid","token":"secret-token"},"firmware":{"url":"https://example.invalid/update.bin"}}""") }
        val result = client.discover("https://example.invalid/", "device", "client", "{}")
        assertTrue(result is Bootstrap.Result.ActivationCode)
        assertEquals("secret-code", (result as Bootstrap.Result.ActivationCode).code)
        assertFalse(result.toString().contains("secret"))
    }

    @Test fun invalidIdentityAndPayloadNeverCallTransport() {
        var calls = 0
        val client = BootstrapClient { _, _, _ -> calls++; BootstrapClient.Response(200, "{}") }
        assertSame(Bootstrap.Result.Invalid, client.discover("https://example.invalid", "device\nInjected", "client", "{}"))
        assertSame(Bootstrap.Result.Invalid, client.discover("https://example.invalid", "device", "client", "[]"))
        assertEquals(0, calls)
    }
}
