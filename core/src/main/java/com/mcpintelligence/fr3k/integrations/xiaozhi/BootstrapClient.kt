package com.mcpintelligence.fr3k.integrations.xiaozhi

import java.net.URI
import javax.net.ssl.HttpsURLConnection
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.*

/** Explicit, user-initiated discovery only. Never downloads or installs firmware. */
class BootstrapClient(private val post: (String, Map<String, String>, String) -> Response = ::httpsPost) {
    class Response(val status: Int, val body: String)
    companion object {
        /** mac_address is a protocol field carrying an app-generated virtual ID, not a hardware address. */
        fun androidSystemInfo(device: String, name: String, version: String): String = buildJsonObject {
            put("mac_address", device)
            put("application", buildJsonObject { put("name", name); put("version", version) })
        }.toString()
    }

    fun discover(endpoint: String, deviceId: String, clientId: String, systemInfoJson: String): Bootstrap.Result {
        val valid = try {
            val uri = URI(endpoint)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
        } catch (_: IllegalArgumentException) { false } catch (_: java.net.URISyntaxException) { false }
        if (!valid || listOf(deviceId, clientId).any { it.isBlank() || it.any { c -> c.isISOControl() } } || systemInfoJson.length > 65536) return Bootstrap.Result.Invalid
        try {
            if (Json.parseToJsonElement(systemInfoJson) !is JsonObject) return Bootstrap.Result.Invalid
            val response = post(endpoint, mapOf("Device-Id" to deviceId, "Client-Id" to clientId, "Activation-Version" to "1", "Content-Type" to "application/json"), systemInfoJson)
            if (response.status != 200 || response.body.length > 65536) return Bootstrap.Result.Invalid
            val root = Json.parseToJsonElement(response.body) as? JsonObject ?: return Bootstrap.Result.Invalid
            val activation = root["activation"] as? JsonObject
            if (activation != null && (activation.containsKey("code") || activation.containsKey("challenge"))) {
                val code = (activation["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                return if (code != null && code.length in 1..128 && code.none { it.isISOControl() }) Bootstrap.Result.ActivationCode(code) else Bootstrap.Result.ActivationPending
            }
            return Bootstrap.parse(response.body)
        } catch (_: Exception) {
            return Bootstrap.Result.Invalid
        }
    }
}

private fun httpsPost(endpoint: String, headers: Map<String, String>, body: String): BootstrapClient.Response {
    val connection = URI(endpoint).toURL().openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.requestMethod = "POST"
        connection.doOutput = true
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val status = connection.responseCode
        if (status != 200) return BootstrapClient.Response(status, "")
        val bytes = connection.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > 65536) throw java.io.IOException("response too large")
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        return BootstrapClient.Response(status, String(bytes, StandardCharsets.UTF_8))
    } finally { connection.disconnect() }
}
