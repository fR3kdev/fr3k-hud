package com.mcpintelligence.fr3k.integrations.providers

import com.mcpintelligence.fr3k.core.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class AgentProvider(val id: String, val title: String, val endpoint: String, val signupUrl: String, val keyUrl: String, val guidance: String, val requestedModel: String?) {
    GROQ("groq", "Groq (recommended free tier)", "https://api.groq.com/openai/v1", "https://console.groq.com", "https://console.groq.com/keys", "Sign up, create an API key, then paste it below. Free tier has rate and usage limits; it is not unlimited.", "openai/gpt-oss-120b"),
    OPENCODE_GO("opencode-go", "OpenCode Go", "https://opencode.ai/zen/go/v1", "https://opencode.ai/go", "https://opencode.ai/auth", "Sign in, subscribe to Go, then create a workspace API key. Subscription limits apply.", "deepseek-v4.1-flash"),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai", "https://openrouter.ai/settings/keys", "Create an API key. With no available credit, choose a zero-price model. Free model rate limits still apply.", "deepseek/deepseek-v4.1-flash");
    companion object { fun fromId(id: String) = entries.firstOrNull { it.id == id } }
}
data class ProviderModel(val id: String, val name: String, val free: Boolean = false)
enum class ValidationStatus { VALID, INVALID_KEY, RATE_LIMITED, UNAVAILABLE, ERROR }
data class ProviderValidation(val provider: AgentProvider, val status: ValidationStatus, val models: List<ProviderModel> = emptyList(), val defaultModel: String? = null, val message: String = "", val inferenceVerified: Boolean = false)

/** Keys never enter results, preferences or diagnostic messages. Inject encrypted storage in the application. */
class ProviderSetupService(
    private val settings: AppSettings,
    private val writeSecret: (String, String) -> Unit,
    private val endpoint: (AgentProvider) -> String = { it.endpoint },
) {
    suspend fun validate(provider: AgentProvider, key: String): ProviderValidation = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext ProviderValidation(provider, ValidationStatus.INVALID_KEY, message = "Enter an API key.")
        try {
            val freeOnly = if (provider == AgentProvider.OPENROUTER) {
                val auth = request(provider, key, "/key").getJSONObject("data")
                auth.optBoolean("is_free_tier", true) || (!auth.isNull("limit_remaining") && auth.optDouble("limit_remaining", 0.0) <= 0.0)
            } else false
            val data = request(provider, key, "/models").getJSONArray("data")
            val models = (0 until data.length()).map { data.getJSONObject(it) }.mapNotNull {
                val id = it.optString("id")
                val pricing = it.optJSONObject("pricing")
                val free = pricing?.optString("prompt")?.toDoubleOrNull() == 0.0 && pricing.optString("completion").toDoubleOrNull() == 0.0
                if (id.isBlank()) null else ProviderModel(id, it.optString("name", id), free)
            }
            val default = if (provider == AgentProvider.OPENROUTER) {
                if (freeOnly) models.firstOrNull { it.id == "openrouter/free" && it.free }?.id ?: models.firstOrNull { it.free }?.id
                else models.firstOrNull { it.id == "deepseek/deepseek-v4.1-flash" }?.id
            } else models.firstOrNull { it.id == provider.requestedModel }?.id
            ProviderValidation(provider, ValidationStatus.VALID, models, default,
                if (provider.requestedModel != null && models.none { it.id == provider.requestedModel }) "Requested ${provider.requestedModel} unavailable. Choose an advertised model." else "Models loaded; inference not yet verified.")
        } catch (e: CancellationException) { throw e }
        catch (e: ProviderHttpException) { ProviderValidation(provider, when(e.code) { 401,403 -> ValidationStatus.INVALID_KEY; 429 -> ValidationStatus.RATE_LIMITED; else -> ValidationStatus.ERROR }, message = "Provider HTTP ${e.code}. Check key, access and limits.") }
        catch (_: Exception) { ProviderValidation(provider, ValidationStatus.ERROR, message = "Provider connection or response failed. Try again.") }
    }

    val state get() = settings.settings

    fun selectedModel(s: AppSettings.Settings = settings.settings.value): String = when (s.activeAgentProvider) {
        "groq" -> s.groqModel
        "opencode-go" -> s.opencodegoModel
        else -> s.openrouterModel
    }
    fun keySlot(provider: AgentProvider, s: AppSettings.Settings = settings.settings.value): String = when(provider) {
        AgentProvider.GROQ -> s.groqApiKeyKey
        AgentProvider.OPENCODE_GO -> s.opencodegoApiKeyKey
        AgentProvider.OPENROUTER -> s.openrouterApiKeyKey
    }
    /** Last successful authenticated inference, not a promise of current network availability. */
    fun isReady(readSecret: (String) -> String?): Boolean {
        val s = settings.settings.value
        val provider = AgentProvider.fromId(s.activeAgentProvider) ?: return false
        val key = readSecret(keySlot(provider, s))?.takeIf { it.isNotBlank() } ?: return false
        return s.verifiedAgentSelection == stamp(provider, selectedModel(s), key)
    }
    private fun stamp(provider: AgentProvider, model: String, key: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest("${provider.id}\n$model\n$key".toByteArray()).joinToString("") { "%02x".format(it) }

    /** Explicit user action: performs a tiny billable inference before securely saving. */
    suspend fun saveSelection(provider: AgentProvider, key: String, model: String, catalog: ProviderValidation): ProviderValidation = withContext(Dispatchers.IO) {
        if (key.isBlank() || catalog.provider != provider || catalog.status != ValidationStatus.VALID || catalog.models.none { it.id == model })
            return@withContext ProviderValidation(provider, ValidationStatus.UNAVAILABLE, message = "Validate the key and choose an advertised model first.")
        try {
            val response = request(provider, key, "/chat/completions", JSONObject().put("model", model).put("stream", false)
                .put("max_tokens", 1024).put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK."))))
            val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            check(message.optString("role") == "assistant" && message.optString("content").isNotBlank() && !message.isNull("content"))
            writeSecret(keySlot(provider), key)
            settings.update { s ->
                val selected = when(provider) {
                    AgentProvider.GROQ -> s.copy(groqModel = model)
                    AgentProvider.OPENCODE_GO -> s.copy(opencodegoModel = model)
                    AgentProvider.OPENROUTER -> s.copy(openrouterModel = model)
                }
                selected.copy(activeAgentProvider = provider.id, verifiedAgentSelection = stamp(provider, model, key))
            }
            catalog.copy(defaultModel = model, inferenceVerified = true, message = "Authenticated inference verified; selection saved.")
        } catch(e: CancellationException) { throw e }
        catch(e: ProviderHttpException) {
            if (e.code == 402 && provider == AgentProvider.OPENROUTER && model != "openrouter/free" &&
                catalog.models.any { it.id == "openrouter/free" && it.free }) {
                val fallback = saveSelection(provider, key, "openrouter/free", catalog)
                fallback.copy(message = "Paid model has insufficient credit. Switched to openrouter/free. ${fallback.message}")
            } else catalog.copy(status = if(e.code == 429) ValidationStatus.RATE_LIMITED else ValidationStatus.ERROR, message = "Inference HTTP ${e.code}. Selection not saved.")
        }
        catch(_: Exception) { catalog.copy(status = ValidationStatus.ERROR, message = "Inference or secure save failed. Selection not verified.") }
    }

    internal fun request(provider: AgentProvider, key: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = URL(endpoint(provider).trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000; conn.readTimeout = 60000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            if (body != null) { conn.requestMethod = "POST"; conn.doOutput = true; conn.outputStream.bufferedWriter().use { it.write(body.toString()) } }
            val code = conn.responseCode
            if (code !in 200..299) throw ProviderHttpException(code)
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }
}
internal class ProviderHttpException(val code: Int): Exception("Provider HTTP $code")
