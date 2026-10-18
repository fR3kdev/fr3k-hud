package com.mcpintelligence.fr3k.integrations.blackwave

import org.json.JSONObject
import org.json.JSONTokener

/** Input bounds only. BLACKWAVE validates the published action's exact schema and approval. */
object BlackwaveLocalParameters {
    fun parse(raw: String?): JSONObject {
        if (raw == null) return JSONObject()
        require(raw.toByteArray(Charsets.UTF_8).size <= 4096) { "Local action parameters exceed 4 KiB" }
        var depth = 0; var tokens = 0; var quoted = false; var escaped = false
        for (c in raw) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 4 && ++tokens <= 64) }
                '}', ']' -> { depth--; require(depth >= 0) }
                ',', ':' -> require(++tokens <= 64)
            }
        }
        require(depth == 0 && !quoted)
        val tokensReader = JSONTokener(raw)
        val value = tokensReader.nextValue()
        require(value is JSONObject && tokensReader.nextClean() == 0.toChar())
        return value.also { require(it.length() <= 8) }
    }
}
