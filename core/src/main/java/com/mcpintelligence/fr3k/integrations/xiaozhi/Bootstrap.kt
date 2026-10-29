package com.mcpintelligence.fr3k.integrations.xiaozhi

import java.net.URI
import kotlinx.serialization.json.*

/** Response-only discovery; never fetches firmware or initiates activation. */
object Bootstrap {
    sealed class Result {
        class Ready(val url: String, val token: String?, val version: Int) : Result() {
            override fun toString() = "Ready(url=<redacted>, token=<redacted>, version=$version)"
        }
        object Invalid : Result()
        object ActivationPending : Result()
        class ActivationCode(val code: String) : Result() {
            override fun toString() = "ActivationCode(<redacted>)"
        }
    }

    @JvmStatic fun parse(body: String): Result {
        if (body.length > 65536) return Result.Invalid
        return try {
            val root = Json.parseToJsonElement(body) as? JsonObject ?: return Result.Invalid
            val ws = root["websocket"] as? JsonObject ?: return Result.Invalid
            fun string(name: String): String? = (ws[name] as? JsonPrimitive)?.let {
                if (it.isString) it.content else null
            }
            val url = string("url") ?: return Result.Invalid
            val uri = URI(url)
            if (uri.scheme != "wss" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) return Result.Invalid
            val token = string("token")
            if (ws.containsKey("token") && (token == null || token.any { it.isISOControl() })) return Result.Invalid
            val v = ws["version"]
            val version = if (v == null) 1 else (v as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return Result.Invalid
            if (version !in 1..3) return Result.Invalid
            Result.Ready(url, token, version)
        } catch (_: java.net.URISyntaxException) {
            Result.Invalid
        } catch (_: IllegalArgumentException) {
            Result.Invalid
        }
    }
}
