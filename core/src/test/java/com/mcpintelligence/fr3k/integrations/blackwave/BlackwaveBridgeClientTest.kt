package com.mcpintelligence.fr3k.integrations.blackwave

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BlackwaveBridgeClientTest {
    private fun client(server: MockWebServer) = BlackwaveBridgeClient(
        { server.url("/").toString().trimEnd('/') }, { "fixture-only" }, { "fixture-client" },
    )

    @Test fun checksCanonicalHealthRoute() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"ok","api":"fr3k-blackwave-mobile/1"}"""))
            assertTrue(client(server).isAvailable())
            assertEquals("/mobile/v1/health", server.takeRequest().path)
        }
    }

    @Test fun sendsCanonicalClientHeaderAndAcceptsNullableExpiry() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"role_id":"bw:role:observer","expires_at":null}"""))
            assertEquals("bw:role:observer", client(server).fetchRole().getOrThrow().role_id)
            val request = server.takeRequest()
            assertEquals("/mobile/v1/role", request.path)
            assertEquals("fixture-client", request.getHeader("X-Blackwave-Client"))
            assertEquals("Bearer fixture-only", request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Client-Id"))
        }
    }

    @Test fun preservesPowerValidationAndCachedEvidence() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{
                "identity":{"model_id":"m5stack-tab5"},
                "power":{"battery":"NOT REPORTED","physical_charging_verified":false},
                "validation":{"completion_percent":null,"state":"N/P"},
                "live":false,"cached":true,"observation_status":"cached"
            }"""))
            val status = client(server).fetchDeviceStatus("m5stack-tab5").getOrThrow()
            assertEquals("/mobile/v1/devices/m5stack-tab5", server.takeRequest().path)
            assertEquals(JsonNull, status.validation["completion_percent"])
            assertTrue(status.power.containsKey("physical_charging_verified"))
            assertFalse(status.live)
            assertTrue(status.cached)
        }
    }

    @Test fun authorizationFailureIsNotSuccessfulRole() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
            assertTrue(client(server).fetchRole().isFailure)
        }
    }

    @Test fun selfSignedTrustRelaxationIsStrictlyLoopbackOnly() {
        // Only loopback hostnames/IPs are eligible for the relaxed cert-chain
        // trust used for the standalone-phone Termux BLACKWAVE server. Remote
        // endpoints keep normal Android TLS trust (fail-closed against a
        // self-signed remote cert).
        assertTrue(BlackwaveBridgeClient.isLoopbackHost("127.0.0.1"))
        assertTrue(BlackwaveBridgeClient.isLoopbackHost("localhost"))
        assertTrue(BlackwaveBridgeClient.isLoopbackHost("LOCALHOST"))
        assertTrue(BlackwaveBridgeClient.isLoopbackHost("::1"))
        assertFalse(BlackwaveBridgeClient.isLoopbackHost("192.168.1.117"))
        assertFalse(BlackwaveBridgeClient.isLoopbackHost("blackwave.local"))
        assertFalse(BlackwaveBridgeClient.isLoopbackHost("fleet.example.com"))
        assertFalse(BlackwaveBridgeClient.isLoopbackHost(""))
    }

    @Test fun roleContractPinsMobileRouteWithClientHeaders() = runTest {
        // The role route is the contract between HUD and the blackwave mobile
        // gateway: GET /mobile/v1/role with X-Blackwave-Client + Bearer auth.
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"role_id":"bw:role:observer","identity":"fixture-client",
                     "trust_tier":"observer","allowed_scopes":["fleet.view","ota.view"],
                     "device_access":"readonly","delegation_depth":0,
                     "capabilities_map":{"fleet.view":"bw.cap.fleet"}}
                    """.trimIndent(),
                ),
            )
            val role = client(server).fetchRole().getOrThrow()
            assertEquals("bw:role:observer", role.role_id)
            assertEquals("fixture-client", role.identity)
            assertEquals("observer", role.trust_tier)
            assertTrue(role.allowed_scopes.contains("ota.view"))
            assertEquals("bw.cap.fleet", role.capabilityIdForScope("fleet.view"))
            val request = server.takeRequest()
            assertEquals("/mobile/v1/role", request.path)
            assertEquals("fixture-client", request.getHeader("X-Blackwave-Client"))
            assertEquals("Bearer fixture-only", request.getHeader("Authorization"))
        }
    }

    @Test fun roleRouteJoinsTrailingSlashEndpointWithoutDoublePath() = runTest {
        // A configured endpoint root with a trailing slash must still produce
        // /mobile/v1/role (not //mobile/v1/role) when the client joins a route.
        MockWebServer().use { server ->
            val url = server.url("/").toString().trimEnd('/')
            val trailingSlashClient = BlackwaveBridgeClient(
                { "$url/" }, { "tk" }, { "cid" },
            )
            server.enqueue(MockResponse().setBody("""{"role_id":"bw:role:observer"}"""))
            val role = trailingSlashClient.fetchRole().getOrThrow()
            assertEquals("bw:role:observer", role.role_id)
            // The path seen by the server must be exactly one slash.
            assertEquals("/mobile/v1/role", server.takeRequest().path)
        }
    }
}
