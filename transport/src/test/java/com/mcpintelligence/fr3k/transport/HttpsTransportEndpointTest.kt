package com.mcpintelligence.fr3k.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.MalformedURLException

/**
 * Endpoint-normalisation contract for the Hermes transport.
 *
 * The configured value is the envelope POST *root* and must join `/envelope`
 * with exactly one `/` separator, never producing `//envelope` or a malformed
 * URL. A missing scheme must fail loudly (not be silently swallowed into a
 * broken POST that decays to an "unreachable" fallback).
 */
class HttpsTransportEndpointTest {

    @Test fun defaultLoopbackJoinsEnvelopeWithSingleSeparator() {
        val url = HttpsTransport.buildEnvelopeUrl("http://127.0.0.1:8082")
        assertEquals("http://127.0.0.1:8082/envelope", url.toString())
    }

    @Test fun trailingSlashNeverProducesDoublePath() {
        val url = HttpsTransport.buildEnvelopeUrl("http://127.0.0.1:8082/")
        assertEquals("http://127.0.0.1:8082/envelope", url.toString())
    }

    @Test fun embeddedApiRootIsJoinedExactlyOnce() {
        val url = HttpsTransport.buildEnvelopeUrl("https://hermes.local/api/v1/agent")
        assertEquals("https://hermes.local/api/v1/agent/envelope", url.toString())
    }

    @Test fun trailingSlashOnEmbeddedRootIsStillSingle() {
        val url = HttpsTransport.buildEnvelopeUrl("https://hermes.local/api/v1/agent/")
        assertEquals("https://hermes.local/api/v1/agent/envelope", url.toString())
    }

    @Test fun httpsRootIsPreserved() {
        val url = HttpsTransport.buildEnvelopeUrl("https://127.0.0.1:8878")
        assertEquals("https://127.0.0.1:8878/envelope", url.toString())
    }

    @Test fun missingSchemeThrowsInsteadOfSilentMalformedUrl() {
        // A bare host was previously passed to URL() and swallowed by
        // runCatching in send()/isAvailable(), masking a misconfiguration.
        assertThrows(IllegalArgumentException::class.java) {
            HttpsTransport.buildEnvelopeUrl("127.0.0.1:8082")
        }
    }

    @Test fun emptyOrSlashOnlyRootThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpsTransport.buildEnvelopeUrl("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpsTransport.buildEnvelopeUrl("///")
        }
    }

    @Test fun malformedSchemeStillThrowsWhenUnparsable() {
        // http://localhost:port with a non-numeric port fails at URL parse.
        assertThrows(MalformedURLException::class.java) {
            HttpsTransport.buildEnvelopeUrl("http://localhost:notaport")
        }
    }
}