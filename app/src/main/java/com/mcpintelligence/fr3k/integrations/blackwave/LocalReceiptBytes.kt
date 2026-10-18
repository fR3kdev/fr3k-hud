package com.mcpintelligence.fr3k.integrations.blackwave

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Python-compatible wire bytes for the integer-only mobile contract subset.
 * Floating-point values are refused until Python formatting conformance is qualified.
 * This does not sign, enroll, dispatch or replace host replay protection.
 */
object LocalReceiptBytes {
    private val codePointOrder = Comparator<String> { a, b ->
        val left = a.codePoints().toArray()
        val right = b.codePoints().toArray()
        var result = 0
        for (i in 0 until minOf(left.size, right.size)) {
            if (left[i] != right[i]) { result = left[i].compareTo(right[i]); break }
        }
        if (result == 0) left.size.compareTo(right.size) else result
    }

    fun canonical(value: Any?, ascii: Boolean = false): ByteArray = encode(value, ascii, 0).toByteArray(Charsets.UTF_8)

    private fun encode(value: Any?, ascii: Boolean, depth: Int): String {
        require(depth <= 32) { "JSON nesting exceeds contract bound" }
        return when (value) {
            null, JSONObject.NULL -> "null"
            is Boolean -> value.toString()
            is Byte, is Short, is Int, is Long -> value.toString()
            is String -> quote(value, ascii)
            is JSONObject -> value.keys().asSequence().toList().sortedWith(codePointOrder).joinToString(",", "{", "}") {
                quote(it, ascii) + ":" + encode(value.get(it), ascii, depth + 1)
            }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { encode(value.get(it), ascii, depth + 1) }
            else -> error("Unsupported wire value: ${value.javaClass.simpleName}")
        }
    }

    private fun quote(value: String, ascii: Boolean): String = buildString {
        append('"')
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (Character.isHighSurrogate(c)) {
                        require(i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) { "Unpaired surrogate" }
                        if (ascii) { append("\\u%04x".format(c.code)); append("\\u%04x".format(value[++i].code)) }
                        else { append(c); append(value[++i]) }
                    } else {
                        require(!Character.isLowSurrogate(c)) { "Unpaired surrogate" }
                        if (c.code < 32 || ascii && c.code >= 127) append("\\u%04x".format(c.code)) else append(c)
                    }
                }
            }
            i++
        }
        append('"')
    }

    fun identityRef(rawPublicKey: ByteArray): String {
        require(rawPublicKey.size == 32) { "Ed25519 public key must be 32 bytes" }
        return "bw:ed25519:sha256:" + sha256(rawPublicKey).hex()
    }

    fun configImage(profile: JSONObject): ByteArray {
        val payload = canonical(profile, ascii = true) // FR3KCFG2 uses Python ensure_ascii=True.
        require(payload.size <= 256 * 1024)
        return ByteBuffer.allocate(44 + payload.size).put("FR3KCFG2".toByteArray(Charsets.US_ASCII))
            .putInt(payload.size).put(sha256(payload)).put(payload).array()
    }

    fun readConfigPayload(image: ByteArray): ByteArray {
        require(image.size >= 44) { "Truncated configuration image" }
        val buffer = ByteBuffer.wrap(image)
        val magic = ByteArray(8).also { buffer.get(it) }
        require(magic.contentEquals("FR3KCFG2".toByteArray(Charsets.US_ASCII))) { "Invalid magic" }
        val size = buffer.int
        require(size in 0..256 * 1024 && image.size == 44 + size) { "Invalid payload size" }
        val digest = ByteArray(32).also { buffer.get(it) }
        val payload = ByteArray(size).also { buffer.get(it) }
        require(MessageDigest.isEqual(digest, sha256(payload))) { "Configuration digest mismatch" }
        return payload
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
