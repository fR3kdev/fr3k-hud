package com.mcpintelligence.fr3k.capability

import java.security.MessageDigest
import java.util.Collections

internal val PACKAGE_RE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
internal val SHA256_RE = Regex("[0-9a-f]{64}")

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Immutable identity of one installed app build. Every capability in this module is bound to one. */
data class AppIdentity(
    val packageName: String,
    val versionCode: Long,
    val signerSha256: String,
    private val splits: List<String>,
) {
    val splitSha256: List<String> = Collections.unmodifiableList(ArrayList(splits))

    init {
        require(PACKAGE_RE.matches(packageName)) { "invalid package name" }
        require(versionCode >= 0) { "invalid versionCode" }
        require(SHA256_RE.matches(signerSha256)) { "invalid signer digest" }
        require(this.splitSha256.isNotEmpty()) { "at least one APK hash required" }
        require(this.splitSha256.all { SHA256_RE.matches(it) }) { "invalid apk digest" }
    }

    /** Stable across split ordering; changes if any component changes. The recipe binding key. */
    val fingerprint: String
        get() {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(packageName.toByteArray())
            digest.update(versionCode.toString().toByteArray())
            digest.update(signerSha256.toByteArray())
            splitSha256.sorted().forEach { digest.update(it.toByteArray()) }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

    /** Short human label for plan rendering: package plus a fingerprint prefix. */
    val label: String get() = "$packageName@$versionCode/${fingerprint.take(12)}"
}
