package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import com.mcpintelligence.fr3k.capability.AppIdentity
import java.io.File

data class PatchRequest(
    val identity: AppIdentity,
    val sourceApk: File,
    val moduleApk: File?,
    val label: String,
)

data class PatchResult(
    val outputApk: File,
    val sha256: String,
    val sheet: PreparationSheet,
)

/** Every patch engine must obey this contract; the shared test suite pins the behaviour. */
interface PatchEngine {
    val id: String
    suspend fun patch(request: PatchRequest, workspace: File): PatchResult
}

object PatchContract {
    const val MAX_BYTES = 512L * 1024 * 1024

    /** Refuses a source whose real bytes do not match the identity we are about to modify. */
    fun requireSourceMatchesIdentity(sourceApk: File, identity: AppIdentity, hashes: List<String>) {
        require(sourceApk.isFile && sourceApk.canRead()) { "source APK is not readable" }
        require(sourceApk.length() in 1..MAX_BYTES) { "source APK outside the 512 MiB budget" }
        require(hashes.isNotEmpty()) { "identity carries no APK hashes" }
        val digest = ApkHashing.sha256(sourceApk)
        require(hashes.contains(digest)) {
            "staged APK hash $digest is not part of ${identity.label}"
        }
    }
}

object ApkHashing {
    fun sha256(file: File): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                hash.update(buffer, 0, read)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
