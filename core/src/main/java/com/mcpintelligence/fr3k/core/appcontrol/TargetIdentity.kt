package com.mcpintelligence.fr3k.core.appcontrol

import java.util.Collections

internal fun validPackage(value: String) = value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
internal fun validDigest(value: String) = value.matches(Regex("[0-9a-f]{64}"))

/** Split hashes ordered base first then by split name. Certificate rotation is unsupported. */
class TargetIdentity(val packageName: String, val versionCode: Long,
                     val signerSha256: String, splitSha256: List<String>) {
    val splitSha256: List<String> = Collections.unmodifiableList(ArrayList(splitSha256))
    init {
        require(validPackage(packageName)) { "invalid package name" }
        require(versionCode >= 0)
        require(validDigest(signerSha256)) { "invalid signer digest" }
        require(this.splitSha256.isNotEmpty() && this.splitSha256.all(::validDigest))
    }
    override fun equals(other: Any?): Boolean = other is TargetIdentity &&
        packageName == other.packageName && versionCode == other.versionCode &&
        signerSha256 == other.signerSha256 && splitSha256 == other.splitSha256
    override fun hashCode(): Int = listOf(packageName, versionCode, signerSha256, splitSha256).hashCode()
}
