package com.mcpintelligence.fr3k.integrations.opencodego

import com.mcpintelligence.fr3k.core.AiProvider
import com.mcpintelligence.fr3k.core.ProviderHealth
import com.mcpintelligence.fr3k.core.ProviderIds
import com.mcpintelligence.fr3k.protocol.AgentAskRequest
import com.mcpintelligence.fr3k.protocol.AgentAskResponse
import com.mcpintelligence.fr3k.protocol.Capabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** A model OpenCode Go advertises in the `/models` catalogue. */
data class OpenCodeGoModel(val id: String, val name: String)

/**
 * OpenCode Go provider — the user's paid alternative to OpenRouter.
 *
 * OpenCode Go (https://opencode.ai/zen/go) is a subscription plan that serves
 * a curated lineup of open coding models over an OpenAI-compatible
 * chat-completions endpoint. Unlike OpenCode Zen, every request must carry the
 * user's subscription API key as a bearer token, so [requiresApiKey] is true.
 *
 * The base URL is read through [endpointProvider] at call time so a change in
 * Settings takes effect without rebuilding the provider. The API key is read
 * through [apiKeyProvider] at call time and is never logged or rendered
 * unmasked.
 */
class OpenCodeGoProvider(
    private val endpointProvider: () -> String = { DEFAULT_ENDPOINT },
    private val apiKeyProvider: () -> String?,
    private val defaultModel: String = DEFAULT_MODEL,
) : AiProvider, com.mcpintelligence.fr3k.core.tools.AgentSessionBackend {

    override val id: String = ProviderIds.OPENCODE_GO
    override val displayName: String = "OpenCode Go"
    override val capabilities: Set<String> = setOf(Capabilities.AGENT_ASK)
    override val requiresNetwork: Boolean = true
    override val requiresApiKey: Boolean = true

    @Volatile
    private var currentModel: String = defaultModel

    @Volatile
    private var cachedModels: List<OpenCodeGoModel> = emptyList()

    private val endpoint: String get() = endpointProvider().trimEnd('/')

    /** The model the next [ask] would use. */
    fun selectedModel(): String = currentModel

    /** Switch which model the provider asks against. */
    fun setModel(model: String) {
        if (model.isNotBlank()) currentModel = model
    }

    /** Last fetched catalogue (empty until [refreshModels]). */
    fun cachedModels(): List<OpenCodeGoModel> = cachedModels

    /** Native chat-completions with tool calls for the shared app-wide session. */
    override suspend fun complete(
        messages: JSONArray,
        tools: List<com.mcpintelligence.fr3k.core.tools.AgentTool>,
    ): JSONObject = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("OpenCode Go is not configured: no API key stored. Add one in Settings.")
        val functions = JSONArray()
        tools.forEach { tool ->
            val properties = JSONObject()
            val names = when (tool.capability) {
                "browser" -> listOf("action", "url", "selector", "text", "x", "y")
                "termux" -> listOf("command", "cwd")
                else -> listOf("action", "deviceId", "url")
            }
            names.forEach { properties.put(it, JSONObject().put("type", "string")) }
            functions.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", com.mcpintelligence.fr3k.core.tools.SharedAgentSession.functionName(tool.id))
                .put("description", tool.description)
                .put("parameters", JSONObject().put("type", "object").put("properties", properties)
                    .put("additionalProperties", false))))
        }
        val body = JSONObject().put("model", currentModel).put("messages", messages)
            .put("stream", false).put("max_tokens", 2048)
        if (tools.isNotEmpty()) body.put("tools", functions).put("tool_choice", "auto").put("parallel_tool_calls", false)
        val conn = (URL("$endpoint/chat/completions").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException(when (code) {
                401, 403 -> "OpenCode Go rejected the key/access (HTTP $code). Check Settings."
                429 -> "OpenCode Go rate limit reached. Try later."
                else -> "OpenCode Go HTTP $code. Check the selected model and endpoint."
            })
            val response = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val message = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IllegalStateException("OpenCode Go returned no assistant message")
            JSONObject(message.toString().replace(key, "[redacted]"))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { throw IllegalStateException((e.message ?: "OpenCode Go request failed").replace(key, "[redacted]")) }
        finally { conn.disconnect() }
    }

    /** Re-fetch the Go catalogue. Never throws. */
    suspend fun refreshModels(): Result<List<OpenCodeGoModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKeyProvider()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("OpenCode Go is not configured: no API key stored.")
            val conn = (URL("$endpoint/models").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $key")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw RuntimeException("HTTP $code: ${conn.responseMessage}")
                val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val data = JSONObject(raw).optJSONArray("data") ?: JSONArray()
                cachedModels = (0 until data.length()).mapNotNull { i ->
                    data.optJSONObject(i)?.let { o ->
                        val modelId = o.optString("id")
                        if (modelId.isBlank()) null
                        else OpenCodeGoModel(id = modelId, name = o.optString("name", modelId))
                    }
                }
                cachedModels
            } finally {
                conn.disconnect()
            }
        }
    }

    override suspend fun ask(request: AgentAskRequest): AgentAskResponse = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            return@withContext AgentAskResponse(
                text = "OpenCode Go is not configured: no API key stored. Add one in Settings.",
                model = currentModel,
            )
        }
        runCatching {
            val model = request.model?.takeIf { it.isNotBlank() } ?: currentModel
            val body = JSONObject().apply {
                put("model", model)
                put("stream", false)
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", request.prompt) })
                    },
                )
                request.context?.let { put("context", it.toString()) }
                put("max_tokens", request.maxTokens ?: 2048)
            }
            val conn = (URL("$endpoint/chat/completions").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                requestMethod = "POST"
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }
            try {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()); it.flush() }
                val code = conn.responseCode
                val raw = if (code in 200..299) {
                    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                } else {
                    val err = conn.errorStream
                        ?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } }
                        ?: ""
                    throw RuntimeException("HTTP $code: ${conn.responseMessage}${if (err.isNotEmpty()) " — ${err.take(200)}" else ""}")
                }
                val resp = JSONObject(raw)
                val msg = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val text = msg?.optString("content").orEmpty()
                val reasoning = msg?.optString("reasoning_content").orEmpty().ifBlank { msg?.optString("reasoning").orEmpty() }
                val fromApi = resp.optString("model", model)
                AgentAskResponse(text = text.ifBlank { reasoning }, model = fromApi)
            } finally {
                conn.disconnect()
            }
        }.fold(
            onSuccess = { it },
            onFailure = { err ->
                AgentAskResponse(
                    text = "OpenCode Go error: ${(err.message ?: err::class.java.simpleName).replace(key, "[redacted]")}",
                    model = currentModel,
                )
            },
        )
    }

    override suspend fun health(): ProviderHealth = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            ProviderHealth(online = false, model = currentModel, message = "NOT CONFIGURED")
        } else {
            runCatching {
                val conn = (URL("$endpoint/models").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $key")
                }
                try {
                    when (val code = conn.responseCode) {
                        in 200..299 -> ProviderHealth(
                            online = true,
                            model = currentModel,
                            message = "${cachedModels.size} models cached",
                        )
                        401, 403 -> ProviderHealth(online = false, model = currentModel, message = "invalid key (HTTP $code)")
                        else -> ProviderHealth(online = false, model = currentModel, message = "HTTP $code")
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrElse {
                ProviderHealth(online = false, model = currentModel, message = (it.message ?: "offline").replace(key, "[redacted]"))
            }
        }
    }

    companion object {
        /** Default OpenAI-compatible base URL for OpenCode Go. */
        const val DEFAULT_ENDPOINT = "https://opencode.ai/zen/go/v1"

        /**
         * Placeholder model. The live catalogue (see [refreshModels]) advertises
         * bare ids such as `kimi-k3` or `deepseek-v4-flash`; the panel prompts
         * the user to pick one.
         */
        const val DEFAULT_MODEL = "opencode-go/default"
    }
}
