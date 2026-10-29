package com.mcpintelligence.fr3k.integrations.xiaozhi

import org.junit.Assert.*
import org.junit.Test

class BootstrapTest {
    @Test fun malformedTransportIsTypedErrorNotException() {
        assertSame(Bootstrap.Result.Invalid, Bootstrap.parse("""{"websocket":{"url":"wss://bad host/"}}"""))
    }

    @Test fun sourceDerivedResponseProducesSafeTransportWithoutFirmwareAuthority() {
        // Synthetic fixture based on local ota.cc and websocket_protocol.cc, not a live capture.
        val result = Bootstrap.parse("""{"websocket":{"url":"wss://example.invalid/voice","token":"secret","version":1},"firmware":{"url":"https://example.invalid/update.bin","version":"99"}}""")
        assertTrue(result is Bootstrap.Result.Ready)
        result as Bootstrap.Result.Ready
        assertEquals("wss://example.invalid/voice", result.url)
        assertEquals("secret", result.token)
        assertFalse(result.toString().contains("secret"))
        assertFalse(result.toString().contains("update.bin"))
    }

    @Test fun rejectsUntrustedAndMalformedResponses() {
        listOf("not json", "{}", """{"websocket":{"url":"ws://example.invalid"}}""",
            """{"websocket":{"url":"wss://u:p@example.invalid"}}""",
            """{"websocket":{"url":"wss://example.invalid","version":9}}""",
            """{"websocket":{"url":"wss://example.invalid","token":"bad\nheader"}}"""
        ).forEach { assertSame(Bootstrap.Result.Invalid, Bootstrap.parse(it)) }
    }

    @Test fun missingVersionDefaultsToOne() {
        val result = Bootstrap.parse("""{"websocket":{"url":"wss://example.invalid"}}""") as Bootstrap.Result.Ready
        assertEquals(1, result.version)
    }
}
