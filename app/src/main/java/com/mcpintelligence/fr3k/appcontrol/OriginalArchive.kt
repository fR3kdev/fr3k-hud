package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import com.mcpintelligence.fr3k.capability.AppIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps a byte-identical, content-addressed copy of the user's own original APKs so every patch is
 * undoable. Bytes are stored once per SHA-256 in an object store; an index manifest per artifact
 * fingerprint records the ordered hashes. Deletion is explicit only: there is no automatic pruning,
 * so an original survives a target uninstall until the operator chooses to remove it.
 */
class OriginalArchive(private val context: Context) {
    companion object { private val archiveLock = Any() }

    data class Entry(val directory: File, val files: List<File>, val identity: AppIdentity)

    fun root(): File = File(context.noBackupFilesDir, "app-control/archive").apply { mkdirs() }

    private fun objectsDir(): File = File(root(), "objects").apply { mkdirs() }
    private fun indexDir(): File = File(root(), "index").apply { mkdirs() }
    private fun objectFile(sha256: String): File = File(objectsDir(), "$sha256.apk")

    fun directoryFor(identity: AppIdentity): File = File(indexDir(), identity.fingerprint)

    fun store(target: StagedTarget): Entry {
        synchronized(archiveLock) {
        val directory = directoryFor(target.identity).apply { mkdirs() }
        val hashes = ArrayList<String>()
        val files = ArrayList<File>()
        (listOf(target.baseApk) + target.splits).forEach { source ->
            require(source.isFile && source.canRead()) { "source ${source.name} is not readable" }
            val hash = ApkHashing.sha256(source)
            val dest = objectFile(hash)
            if (!dest.isFile || ApkHashing.sha256(dest) != hash) {
                val temporary = File(objectsDir(), "$hash-${java.util.UUID.randomUUID()}.tmp")
                try {
                    source.inputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
                    require(ApkHashing.sha256(temporary) == hash)
                    java.nio.file.Files.move(temporary.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } finally { temporary.delete() }
            }
            require(ApkHashing.sha256(dest) == hash) { "archive copy of ${source.name} changed" }
            hashes.add(hash)
            files.add(dest)
        }
        val manifest = File(directory, "identity.json")
        require(hashes == target.identity.splitSha256) { "Archive source does not match its identity" }
        val content = JSONObject().apply {
            put("packageName", target.identity.packageName)
            put("versionCode", target.identity.versionCode)
            put("signerSha256", target.identity.signerSha256)
            put("fingerprint", target.identity.fingerprint)
            put("apkSha256", JSONArray(hashes))
        }.toString(2)
        val atomic = android.util.AtomicFile(manifest)
        val output = atomic.startWrite()
        try { output.write(content.toByteArray()); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
        return Entry(directory, files, target.identity)
            }
    }

    fun lookup(identity: AppIdentity): Entry? {
        synchronized(archiveLock) {
        val directory = directoryFor(identity)
        val manifest = File(directory, "identity.json")
        if (!manifest.isFile) {
            // Preserve archives from the pre-object-store layout; never prune or rewrite them.
            val legacy = File(root(), identity.fingerprint)
            val metadata = File(legacy, "identity.json")
            if (!metadata.isFile) return null
            val json = runCatching { JSONObject(metadata.readText()) }.getOrNull() ?: return null
            if (json.optString("fingerprint") != identity.fingerprint) return null
            val files = identity.splitSha256.indices.map { File(legacy, if (it == 0) "base.apk" else "split-$it.apk") }
            if (files.any { !it.isFile } || files.map(ApkHashing::sha256) != identity.splitSha256) return null
            return Entry(legacy, files, identity)
        }
        val json = JSONObject(manifest.readText())
        if (json.optString("fingerprint") != identity.fingerprint) return null
        val recorded = (0 until json.getJSONArray("apkSha256").length())
            .map { json.getJSONArray("apkSha256").getString(it) }
        if (recorded.isEmpty()) return null
        if (recorded != identity.splitSha256) return null
        val files = recorded.map(::objectFile)
        if (files.any { !it.isFile }) return null
        val hashes = files.map { ApkHashing.sha256(it) }
        if (hashes != recorded) return null
        return Entry(directory, files, identity)
            }
    }

    fun contains(identity: AppIdentity): Boolean = lookup(identity) != null

    /** Archived identities, for explicit review and deletion. */
    fun list(): List<AppIdentity> =
        indexDir().listFiles()?.mapNotNull { directory ->
            val manifest = File(directory, "identity.json")
            if (!manifest.isFile) return@mapNotNull null
            runCatching {
                val json = JSONObject(manifest.readText())
                AppIdentity(
                    json.getString("packageName"),
                    json.getLong("versionCode"),
                    json.getString("signerSha256"),
                    (0 until json.getJSONArray("apkSha256").length())
                        .map { json.getJSONArray("apkSha256").getString(it) },
                )
            }.getOrNull()
        }?.sortedBy { it.packageName } ?: emptyList()

    /**
     * Explicit deletion of one archived original. Returns true if a manifest was removed. Object
     * files are garbage-collected only when no remaining index references them.
     */
    fun delete(identity: AppIdentity): Boolean {
        synchronized(archiveLock) {
        val directory = directoryFor(identity)
        val removed = if (directory.isDirectory) directory.deleteRecursively() else false
        if (removed) gcObjects()
        return removed
            }
    }

    private fun gcObjects() {
        val referenced = HashSet<String>()
        var complete = true
        indexDir().listFiles()?.forEach { directory ->
            val manifest = File(directory, "identity.json")
            if (!manifest.isFile) return@forEach
            runCatching {
                val json = JSONObject(manifest.readText())
                val arr = json.getJSONArray("apkSha256")
                (0 until arr.length()).forEach { referenced.add(arr.getString(it)) }
            }.onFailure { complete = false }
        }
        if (!complete) return // A damaged index is not permission to delete its original.
        objectsDir().listFiles()?.forEach { file ->
            if (file.isFile && file.name.removeSuffix(".apk") !in referenced) file.delete()
        }
    }
}
