package com.mcpintelligence.fr3k.integrations.opencode

import com.mcpintelligence.fr3k.core.AiProvider
import com.mcpintelligence.fr3k.core.ProviderHealth
import com.mcpintelligence.fr3k.protocol.AgentAskRequest
import com.mcpintelligence.fr3k.protocol.AgentAskResponse
import com.mcpintelligence.fr3k.protocol.Capabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenCode Go free-model provider.
 *
 * OpenCode Go ([opencode.ai/zen](https://opencode.ai/zen)) exposes a set of
 * hosted LLM models through an OpenAI-compatible chat completions endpoint at
 * [endpoint]. Free models are accessible with a public bearer key
 * (`Authorization: Bearer public`); no signup, no API key, no card.
 *
 * We default to **big-pickle** because the Zen docs flag it as the current
 * free headliner (the model is promoted to a new state every few weeks — the
 * default tracks whichever model Zen advertises as the headline free model
 * for the current period).
 *
 * Use [setModel] / [availableFreeModels] to inspect or switch between models.
 * The model list is fetched from [modelsEndpoint] on first use and cached
 * in-memory; pull [refreshFreeModels] to force a re-fetch.
 */
class OpenCodeGoProvider(
    /**
     * OpenCode Go's OpenAI-compatible base. Go is the paid subscription
     * ([opencode.ai/docs/go](https://opencode.ai/docs/go)); its models are served under
     * `/zen/go/v1`, not the zero-key `/zen/v1` path.
     */
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val bearerToken: String = "public",
    private val defaultModel: String = DEFAULT_MODEL,
    /**
     * Read at request time, so a key saved in Settings takes effect immediately instead of only on the
     * next process start. Falls back to [bearerToken] when nothing is configured.
     */
    private val bearerTokenProvider: (() -> String)? = null,
    /**
     * OpenCode Go routes on `x-opencode-session` and answers 400 `MissingSessionID` without it. This is
     * the header that made earlier builds fail: the key and the endpoint were fine, the request was not
     * routable. A stable id per conversation is what keeps a conversation on one route.
     */
    private val sessionIdProvider: (() -> String)? = null,
    private val modelRegistry: OpenCodeGoModelRegistry = OpenCodeGoModelRegistry(
        endpoint,
        bearerTokenProvider = bearerTokenProvider,
    ),
) : AiProvider, com.mcpintelligence.fr3k.core.tools.AgentSessionBackend {

    override val id: String = "opencode-go"
    override val displayName: String = "OpenCode Go"
    override val capabilities: Set<String> = setOf(Capabilities.AGENT_ASK)
    override val requiresNetwork: Boolean = true
    override val requiresApiKey: Boolean = false

    @Volatile
    private var currentModel: String = defaultModel

    /** One id for this provider's lifetime, used when nothing better supplies a conversation id. */
    private val fallbackSessionId: String = java.util.UUID.randomUUID().toString()

    /** The routing session actually sent, so tests and diagnostics can assert it. */
    fun routingSessionId(): String =
        sessionIdProvider?.invoke()?.takeIf { it.isNotBlank() } ?: fallbackSessionId

    /** The base URL actually used, so tests and diagnostics can assert it rather than assume it. */
    fun endpointUrl(): String = endpoint

    /** The credential actually sent: the operator's configured key when there is one. */
    fun activeBearerToken(): String =
        bearerTokenProvider?.invoke()?.takeIf { it.isNotBlank() } ?: bearerToken

    fun usingConfiguredKey(): Boolean =
        bearerTokenProvider?.invoke()?.isNotBlank() == true

    fun setModel(modelId: String) {
        if (modelId.isNotBlank()) currentModel = modelId
    }

    fun selectedModel(): String = currentModel

    /**
     * Models this provider can actually talk to. A live `/models` fetch wins; otherwise the documented
     * Go catalogue is used, so the picker is never silently empty on a device with no network.
     */
    fun availableModels(): List<OpenCodeGoModel> =
        modelRegistry.cachedAll().ifEmpty { OpenCodeGoCatalogue.chatCompletions() }

    suspend fun refreshModels(): Result<List<OpenCodeGoModel>> = modelRegistry.refresh()

    override suspend fun ask(request: AgentAskRequest): AgentAskResponse = withContext(Dispatchers.IO) {
        runCatching {
            val model = request.model?.takeIf { it.isNotBlank() } ?: currentModel
            val body = JSONObject().apply {
                put("model", model)
                put("stream", false)
                put("messages", JSONObject.wrap(
                    org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", request.prompt)
                        })
                    }
                ))
                request.context?.let { put("context", it.toString()) }
                put("max_tokens", 2048)
            }
            val url = URL("$endpoint/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                requestMethod = "POST"
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${activeBearerToken()}")
                setRequestProperty("x-opencode-session", routingSessionId())
            }
            try {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()); it.flush() }
                val code = conn.responseCode
                val raw = if (code in 200..299) {
                    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                } else {
                    val err = conn.errorStream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } } ?: ""
                    throw RuntimeException("HTTP $code: ${conn.responseMessage}${if (err.isNotEmpty()) " — $err.take(200)" else ""}")
                }
                val resp = JSONObject(raw)
                val choices = resp.optJSONArray("choices")
                val msg = choices?.optJSONObject(0)?.optJSONObject("message")
                val text = msg?.optString("content").orEmpty()
                val reasoning = msg?.optString("reasoning_content").orEmpty()
                val model = resp.optString("model", model)
                if (text.isBlank() && reasoning.isNotBlank()) {
                    AgentAskResponse(text = reasoning, model = model)
                } else {
                    AgentAskResponse(text = text, model = model)
                }
            } finally {
                conn.disconnect()
            }
        }.fold(
            onSuccess = { it },
            onFailure = { err ->
                AgentAskResponse(
                    text = "opencode-go error: ${err.message ?: err::class.java.simpleName}",
                    model = currentModel,
                )
            }
        )
    }

    /**
     * The shared chat/browser session needs native tool calls, not just a text completion, so OpenCode
     * serves the same agent loop OpenRouter does. Schema comes from the one shared builder.
     */
    override suspend fun complete(
        messages: org.json.JSONArray,
        tools: List<com.mcpintelligence.fr3k.core.tools.AgentTool>,
    ): org.json.JSONObject = withContext(Dispatchers.IO) {
        val token = activeBearerToken()
        val body = org.json.JSONObject().put("model", currentModel).put("messages", messages)
            .put("stream", false).put("max_tokens", 2048)
        if (tools.isNotEmpty()) {
            body.put("tools", com.mcpintelligence.fr3k.core.tools.AgentToolSchema.openAiFunctions(tools))
                .put("tool_choice", "auto")
                .put("parallel_tool_calls", false)
        }
        val conn = (URL("$endpoint/chat/completions").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("x-opencode-session", routingSessionId())
        }
        try {
            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                // The provider's own reason is the only way to fix a malformed request, so it is included
                // — truncated, and with the credential redacted in case the body echoes it.
                val detail = runCatching {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()?.replace(token, "[redacted]")?.replace("\n", " ")?.take(300)
                throw IllegalStateException(
                    when (code) {
                        401, 403 -> "OpenCode Go rejected the key or access (HTTP $code). Check Settings."
                        429 -> "OpenCode Go rate limit reached. Try later or select another model."
                        else -> "OpenCode Go HTTP $code" +
                            (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                    },
                )
            }
            val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val response = org.json.JSONObject(raw)
            val message = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IllegalStateException("OpenCode Go returned no assistant message")
            check(message.optString("role") == "assistant") { "Invalid assistant response" }
            org.json.JSONObject(message.toString().replace(token, "[redacted]"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException((e.message ?: "OpenCode Go request failed").replace(token, "[redacted]"))
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun health(): ProviderHealth = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("$endpoint/models")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${activeBearerToken()}")
                setRequestProperty("x-opencode-session", routingSessionId())
            }
            try {
                val code = conn.responseCode
                if (code in 200..299) {
                    val cached = modelRegistry.cachedAll().size
                    ProviderHealth(
                        online = true,
                        model = currentModel,
                        message = if (cached > 0) "$cached models cached" else "reachable (catalogue not fetched yet)",
                    )
                } else {
                    ProviderHealth(online = false, message = "HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { ProviderHealth(online = false, message = it.message ?: "offline") }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://opencode.ai/zen/go/v1"

        /**
         * A Go model served on the OpenAI-compatible `/chat/completions` shape, which is the only shape
         * that can carry native tool calls and therefore run the shared agent session. Go also lists
         * models served on `/responses` and `/messages` — those need a different client and this app's
         * chat path cannot drive them, so they are not offered as the default.
         */
        const val DEFAULT_MODEL = "mimo-v2.6-pro"
    }
}
