package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import android.content.pm.PackageManager
import com.mcpintelligence.fr3k.capability.AppIdentity
import java.io.File
import java.security.MessageDigest

/** Reads an installed identity: package, version, signer and the ordered APK hashes. */
object AndroidIdentityReader {
    fun of(context: Context, pkg: String): AppIdentity {
        require(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "invalid package" }
        val pm = context.packageManager
        val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = requireNotNull(info.signingInfo) { "no signing info for $pkg" }
        val signers = signing.apkContentsSigners
        require(signers.size == 1) { "multi-signer packages are not supported" }
        val signer = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val application = requireNotNull(info.applicationInfo) { "no application info for $pkg" }
        val paths = listOf(application.sourceDir) + (application.splitSourceDirs?.toList() ?: emptyList()).sortedBy { File(it).name }
        val hashes = paths.map { path ->
            val digest = MessageDigest.getInstance("SHA-256")
            File(path).inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        return AppIdentity(pkg, info.longVersionCode, signer, hashes)
    }
}
