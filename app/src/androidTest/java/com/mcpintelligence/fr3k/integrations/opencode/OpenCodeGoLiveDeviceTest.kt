package com.mcpintelligence.fr3k.integrations.opencode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.AiProviderPreference
import com.mcpintelligence.fr3k.core.tools.AgentSessionBackend
import com.mcpintelligence.fr3k.core.tools.AgentTool
import com.mcpintelligence.fr3k.core.tools.ToolArgs
import com.mcpintelligence.fr3k.core.tools.ToolContext
import com.mcpintelligence.fr3k.core.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Live validation against the operator's OpenCode Go key: the auth header, the tool schema and a real
 * round trip, not a mock. The key is injected by the host into the app's private directory and is moved
 * into SecureStore by this test; nothing here writes key material to a log or a receipt.
 *
 * Skipped (not failed) when no key has been injected, so the suite still runs on a clean device.
 */
@RunWith(AndroidJUnit4::class)
class OpenCodeGoLiveDeviceTest {

    private val app get() = Fr3kApplication.get()
    private val keyFile get() = File(app.filesDir, "opencode-go-key.txt")
    private val receiptFile get() = File(app.filesDir, "opencode-go-live-receipt.json")
    private val healthReceiptFile get() = File(app.filesDir, "opencode-go-health-receipt.json")
    private var storedBefore: String? = null

    @Before fun loadInjectedKey() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        storedBefore = app.secureStore.get(app.settings.settings.value.opencodeApiKeyKey)
    }

    @After fun clearInjection() {
        if (storedBefore != null) {
            app.secureStore.put(app.settings.settings.value.opencodeApiKeyKey, storedBefore!!)
        }
    }

    /**
     * The credential under test, and where it came from. The injected file is deleted once for the whole
     * class (not per test, which would make the second test silently skip), and a key already stored in
     * SecureStore is accepted so the suite still validates the real configured credential.
     */
    private fun injectedKey(): String {
        keyFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        app.secureStore.get(app.settings.settings.value.opencodeApiKeyKey)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        assumeTrue("no OpenCode Go key injected or stored in SecureStore", false)
        error("unreachable")
    }

    private fun keySource(): String = if (keyFile.isFile) "injected_file" else "secure_store"

    companion object {
        /** One cleanup for the class, so every test in it sees the injected credential. */
        @AfterClass @JvmStatic fun removeInjection() {
            runCatching {
                File(Fr3kApplication.get().filesDir, "opencode-go-key.txt").delete()
            }
        }
    }

    /** One tool, so the request really exercises the function-calling schema the session depends on. */
    private fun probeTools(): List<AgentTool> = listOf(
        object : AgentTool {
            override val id = "app.control.inspect"
            override val displayName = "Inspect selected app build"
            override val description =
                "Reads only the selected installed package identity, signer and APK split hashes; never private app data"
            override val capability = "app.control.read"
            override val requiresNetwork = false
            override suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult =
                ToolResult.Failure(error = "probe tool: not executed during a connectivity probe")
        },
    )

    @Test fun theGoKeyAuthenticatesAndTheToolCallingSessionWorksEndToEnd() {
        val key = injectedKey()
        app.secureStore.put(app.settings.settings.value.opencodeApiKeyKey, key)
        app.settings.update { it.copy(aiProviderPreference = AiProviderPreference.AUTO) }

        assertEquals("an OpenCode Go key must take over the app", AiProviderPreference.OPENCODE, app.activeProviderId)
        assertEquals("the provider's own id", "opencode-go", app.routingProvider.id)
        assertEquals("the label must name Go and the key", "OpenCode Go (your key)", app.routingProvider.displayName)
        assertEquals(
            "the endpoint is Go's, not the zero-key path",
            "https://opencode.ai/zen/go/v1",
            app.openCodeProvider.endpointUrl(),
        )
        assertEquals(
            "the credential actually sent is the operator's key",
            key,
            app.openCodeProvider.activeBearerToken(),
        )

        // Candidate models served on the OpenAI-compatible /chat/completions shape.
        val candidates = listOf(
            app.settings.settings.value.opencodeModel.takeIf { it.isNotBlank() },
            OpenCodeGoProvider.DEFAULT_MODEL,
            "deepseek-v4.1-flash",
            "deepseek-v4-flash-vision-exp",
            "mimo-v2.6-flash",
            "mimo-v2.5-pro",
        ).filterNotNull().distinct()

        val backend = app.routingProvider as AgentSessionBackend
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "You are a connectivity probe. Reply briefly."))
            .put(JSONObject().put("role", "user").put("content", "Reply with the single word: ok"))
        val tools = probeTools()

        val attempts = mutableListOf<JSONObject>()
        var winner: String? = null
        var winnerReply: JSONObject? = null
        for (model in candidates) {
            app.openCodeProvider.setModel(model)
            val started = System.currentTimeMillis()
            val outcome = runCatching { runBlocking { backend.complete(messages, tools) } }
            val elapsed = System.currentTimeMillis() - started
            val message = outcome.getOrNull()
            val replyText = message?.optString("content").orEmpty()
            val toolCalls = message?.optJSONArray("tool_calls")?.length() ?: 0
            attempts.add(
                JSONObject()
                    .put("model", model)
                    .put("ok", outcome.isSuccess)
                    .put("elapsedMs", elapsed)
                    .put("hasContent", replyText.isNotBlank())
                    .put("toolCallCount", toolCalls)
                    .put("error", outcome.exceptionOrNull()?.message?.replace(key, "[redacted]") ?: JSONObject.NULL),
            )
            if (outcome.isSuccess && (replyText.isNotBlank() || toolCalls > 0)) {
                winner = model
                winnerReply = message
                break
            }
        }

        // A tool-calling round trip: ask for the tool by name and require a real tool call back.
        var toolRoundTrip = JSONObject().put("attempted", false)
        if (winner != null) {
            app.openCodeProvider.setModel(winner!!)
            val toolMessages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "Use the provided tool when the user asks for a package inspection."))
                .put(JSONObject().put("role", "user").put("content", "Inspect the package com.mcpintelligence.fr3k.patchfixture."))
            val toolOutcome = runCatching { runBlocking { backend.complete(toolMessages, tools) } }
            val calls = toolOutcome.getOrNull()?.optJSONArray("tool_calls")?.length() ?: 0
            toolRoundTrip = JSONObject()
                .put("attempted", true)
                .put("ok", toolOutcome.isSuccess)
                .put("toolCallCount", calls)
                .put(
                    "firstTool",
                    toolOutcome.getOrNull()?.optJSONArray("tool_calls")?.optJSONObject(0)
                        ?.optJSONObject("function")?.optString("name") ?: "",
                )
                .put("error", toolOutcome.exceptionOrNull()?.message?.replace(key, "[redacted]") ?: JSONObject.NULL)
        }

        val receipt = JSONObject()
            .put("keySource", keySource())
            .put("keyStoredInSecureStore", app.secureStore.get(app.settings.settings.value.opencodeApiKeyKey) != null)
            .put("endpoint", "https://opencode.ai/zen/go/v1")
            .put("authHeaderSent", "Bearer <key>")
            .put("routingSessionHeaderSent", "x-opencode-session: " + app.openCodeProvider.routingSessionId())
            .put("candidates", JSONArray(attempts.map { it }))
            .put("winningModel", winner ?: JSONObject.NULL)
            .put("toolRoundTrip", toolRoundTrip)
            .put("replyPreview", winnerReply?.optString("content")?.take(120) ?: "")
            .put("replyModel", winnerReply?.optString("model") ?: "")
        receiptFile.writeText(receipt.toString(2))

        assertTrue(
            "no Go model answered. Attempts: ${attempts.joinToString { it.toString() }}",
            winner != null,
        )
        if ("toolRoundTrip".let { toolRoundTrip.optBoolean("attempted") }) {
            assertTrue(
                "the model answered but produced no tool call, so the chat/browser session cannot use it: $toolRoundTrip",
                toolRoundTrip.optBoolean("ok") && toolRoundTrip.optInt("toolCallCount") > 0,
            )
        }

        // Persist the working model so the app runs on a model that is proven to answer.
        app.settings.update { it.copy(opencodeModel = winner!!) }
        assertEquals(
            "the operator's key stays stored and in charge",
            AiProviderPreference.OPENCODE,
            app.activeProviderId,
        )
    }

    @Test fun healthReportsReachabilityWithTheOperatorsKey() {
        val key = injectedKey()
        app.secureStore.put(app.settings.settings.value.opencodeApiKeyKey, key)
        val health = runBlocking { app.openCodeProvider.health() }
        healthReceiptFile.writeText(
            JSONObject()
                .put("keySource", keySource())
                .put("keyStoredInSecureStore", app.secureStore.get(app.settings.settings.value.opencodeApiKeyKey) != null)
                .put("healthOnline", health.online)
                .put("healthModel", health.model ?: "")
                .put("healthMessage", (health.message ?: "").replace(key, "[redacted]"))
                .toString(2),
        )
        // Health is informational: Go may not expose /models to this key. The session test is the
        // authority on whether the credential works for chat.
        assertTrue(
            "health must report something rather than throw: ${health.message}",
            health.model != null || health.message != null,
        )
    }
}
