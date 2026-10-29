package com.mcpintelligence.fr3k.integrations.providers

import com.mcpintelligence.fr3k.core.AppSettings
import com.mcpintelligence.fr3k.core.tools.AgentSessionBackend
import com.mcpintelligence.fr3k.core.tools.AgentTool
import com.mcpintelligence.fr3k.core.tools.SharedAgentSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A single dynamic backend for every surface of the application-owned session. */
class SelectedProviderBackend(
    private val settings: AppSettings,
    private val setup: ProviderSetupService,
    private val readSecret: (String) -> String?,
) : AgentSessionBackend {
    override suspend fun complete(messages: JSONArray, tools: List<AgentTool>): JSONObject = withContext(Dispatchers.IO) {
        val s = settings.settings.value
        val provider = AgentProvider.fromId(s.activeAgentProvider) ?: error("Select an AI provider in setup.")
        val key = readSecret(setup.keySlot(provider, s))?.takeIf { it.isNotBlank() } ?: error("Add and validate your provider key in setup.")
        try {
            val functions = JSONArray()
            tools.forEach { tool ->
                val properties = JSONObject()
                val names = when(tool.capability) {
                    "browser" -> listOf("action", "url", "selector", "text", "x", "y")
                    "termux" -> listOf("command", "cwd")
                    else -> listOf("action", "deviceId", "url")
                }
                names.forEach { properties.put(it, JSONObject().put("type", "string")) }
                functions.put(JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", SharedAgentSession.functionName(tool.id)).put("description", tool.description)
                    .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("additionalProperties", false))))
            }
            val body = JSONObject().put("model", setup.selectedModel(s)).put("messages", messages).put("stream", false).put("max_tokens", 2048)
            if (tools.isNotEmpty()) body.put("tools", functions).put("tool_choice", "auto").put("parallel_tool_calls", false)
            val response = setup.request(provider, key, "/chat/completions", body)
            val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            check(message.optString("role") == "assistant")
            JSONObject(message.toString().replace(key, "[redacted]"))
        } catch(e: CancellationException) { throw e }
        catch(e: ProviderHttpException) { error("${provider.title} HTTP ${e.code}. Check key, model access, balance or rate limits in setup.") }
        catch(_: Exception) { error("${provider.title} response or connection failed. Check setup and try again.") }
    }
}
