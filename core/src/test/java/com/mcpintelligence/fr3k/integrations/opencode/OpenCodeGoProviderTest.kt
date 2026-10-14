package com.mcpintelligence.fr3k.integrations.opencode

import com.mcpintelligence.fr3k.core.tools.AgentTool
import com.mcpintelligence.fr3k.core.tools.ToolArgs
import com.mcpintelligence.fr3k.core.tools.ToolContext
import com.mcpintelligence.fr3k.core.tools.ToolResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenCode Go is an HTTP contract, and the contract that broke earlier builds was a missing routing
 * header — the key and the endpoint were right, the request was not routable. These tests fail if any
 * request path loses the Authorization or `x-opencode-session` header, or if the provider stops
 * surfacing the API's own reason for a rejection.
 */
class OpenCodeGoProviderTest {

    private fun server(): MockWebServer = MockWebServer().also { it.start() }

    private fun provider(server: MockWebServer, key: String? = "sk-test-key", session: String = "session-abc") =
        OpenCodeGoProvider(
            endpoint = server.url("/v1").toString().trimEnd('/'),
            bearerTokenProvider = { key.orEmpty() },
            sessionIdProvider = { session },
        )

    private fun probeTool() = object : AgentTool {
        override val id = "app.control.inspect"
        override val displayName = "Inspect selected app build"
        override val description = "Reads only the selected installed package identity"
        override val capability = "app.control.read"
        override val requiresNetwork = false
        override suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult =
            ToolResult.Failure(error = "not executed in this test")
    }

    @Test fun theSessionRequestCarriesBothHeadersGoRequires() = runBlocking {
        val mock = server()
        try {
            mock.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""),
            )
            val reply = provider(mock).complete(
                JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
                listOf(probeTool()),
            )
            val request = mock.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer sk-test-key", request.getHeader("Authorization"))
            assertEquals("session-abc", request.getHeader("x-opencode-session"))
            assertEquals("ok", reply.optString("content"))
            // The tool schema must travel with the request, or the session cannot call anything.
            val body = JSONObject(request.body.readUtf8())
            assertEquals(1, body.getJSONArray("tools").length())
            assertEquals(
                "app__control__inspect",
                body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"),
            )
        } finally {
            mock.shutdown()
        }
    }

    @Test fun thePlainAskPathAlsoCarriesTheRoutingHeader() = runBlocking {
        val mock = server()
        try {
            mock.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"choices":[{"message":{"role":"assistant","content":"hello"}}]}"""),
            )
            provider(mock).ask(com.mcpintelligence.fr3k.protocol.AgentAskRequest(prompt = "hi"))
            val request = mock.takeRequest()
            assertEquals("session-abc", request.getHeader("x-opencode-session"))
            assertEquals("Bearer sk-test-key", request.getHeader("Authorization"))
        } finally {
            mock.shutdown()
        }
    }

    @Test fun theModelsProbeCarriesTheOperatorsKey() = runBlocking {
        val mock = server()
        try {
            mock.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"mimo-v2.6-pro"}]}"""))
            provider(mock).refreshModels()
            val request = mock.takeRequest()
            assertEquals("/v1/models", request.path)
            // Routing applies to the chat request paths; the catalogue endpoint only needs the key. The
            // live endpoint returned 40 models on exactly this call.
            assertEquals("Bearer sk-test-key", request.getHeader("Authorization"))
        } finally {
            mock.shutdown()
        }
    }

    @Test fun theApisOwnReasonIsSurfacedInsteadOfAguess() = runBlocking {
        val mock = server()
        try {
            // The exact rejection the live endpoint returns when the routing header is absent.
            mock.enqueue(
                MockResponse().setResponseCode(400).setBody(
                    """{"type":"error","error":{"type":"MissingSessionID","message":"Request is missing x-opencode-session"}}""",
                ),
            )
            val failure = runCatching {
                provider(mock).complete(JSONArray().put(JSONObject().put("role", "user").put("content", "hi")), emptyList())
            }.exceptionOrNull()
            assertTrue("expected a refusal, got $failure", failure is IllegalStateException)
            assertTrue(
                "the API's reason must reach the operator: ${failure?.message}",
                failure?.message.orEmpty().contains("MissingSessionID"),
            )
        } finally {
            mock.shutdown()
        }
    }

    @Test fun aRejectedRequestNeverEchoesTheCredential() = runBlocking {
        val mock = server()
        try {
            mock.enqueue(MockResponse().setResponseCode(401).setBody("rejected sk-test-key"))
            val failure = runCatching {
                provider(mock).complete(JSONArray().put(JSONObject().put("role", "user").put("content", "hi")), emptyList())
            }.exceptionOrNull()
            val message = failure?.message.orEmpty()
            assertFalse("the key must never appear in an error: $message", message.contains("sk-test-key"))
            assertTrue("a rejection must be attributed, not vague: $message", message.contains("401"))
        } finally {
            mock.shutdown()
        }
    }

    @Test fun theDefaultEndpointIsGoNotTheZeroKeyPath() {
        assertEquals("https://opencode.ai/zen/go/v1", OpenCodeGoProvider().endpointUrl())
        assertEquals("mimo-v2.6-pro", OpenCodeGoProvider().selectedModel())
    }

    @Test fun withoutAConfiguredKeyThePublicFallbackIsStillUsed() {
        // The default bearer stays "public" so a fresh install has a working path; an operator's key wins.
        assertEquals("public", OpenCodeGoProvider().activeBearerToken())
        assertFalse(OpenCodeGoProvider().usingConfiguredKey())
        val configured = OpenCodeGoProvider(bearerTokenProvider = { "sk-real" })
        assertEquals("sk-real", configured.activeBearerToken())
        assertTrue(configured.usingConfiguredKey())
    }

    @Test fun theRoutingSessionIsStablePerProviderSoAConversationStaysOnOneRoute() {
        val provider = OpenCodeGoProvider()
        val first = provider.routingSessionId()
        assertTrue(first.isNotBlank())
        assertEquals("the same conversation must not look like a new client on each request", first, provider.routingSessionId())
    }

    @Test fun theCatalogueOffersOnlyModelsThisClientCanActuallyDrive() {
        // /responses and /messages models need a different client, so they must not be offered.
        val ids = OpenCodeGoCatalogue.chatCompletionsModelIds
        assertTrue(ids.contains("mimo-v2.6-pro"))
        assertFalse("grok is served on /responses", ids.contains("grok-4.7"))
        assertFalse("minimax is served on /messages", ids.contains("minimax-m3"))
        assertEquals(ids.size, OpenCodeGoCatalogue.chatCompletions().size)
    }
}
