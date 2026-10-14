package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import com.mcpintelligence.fr3k.capability.AppIdentity
import java.io.File

/** Keeps a byte-identical copy of the user's own original APKs so every patch is undoable. */
class OriginalArchive(private val context: Context) {

    data class Entry(val directory: File, val files: List<File>, val identity: AppIdentity)

    fun root(): File = File(context.noBackupFilesDir, "app-control/archive").apply { mkdirs() }

    fun directoryFor(identity: AppIdentity): File = File(root(), identity.fingerprint)

    fun store(target: StagedTarget): Entry {
        val directory = directoryFor(target.identity).apply { mkdirs() }
        val manifest = File(directory, "identity.json")
        val files = ArrayList<File>()
        (listOf(target.baseApk) + target.splits).forEachIndexed { index, source ->
            val name = if (index == 0) "base.apk" else "split-$index.apk"
            val dest = File(directory, name)
            source.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            require(ApkHashing.sha256(dest) == ApkHashing.sha256(source)) { "archive copy of $name changed" }
            files.add(dest)
        }
        manifest.writeText(org.json.JSONObject().apply {
            put("packageName", target.identity.packageName)
            put("versionCode", target.identity.versionCode)
            put("signerSha256", target.identity.signerSha256)
            put("fingerprint", target.identity.fingerprint)
            put("apkSha256", org.json.JSONArray(files.map { ApkHashing.sha256(it) }))
        }.toString(2))
        return Entry(directory, files, target.identity)
    }

    fun lookup(identity: AppIdentity): Entry? {
        val directory = directoryFor(identity)
        val manifest = File(directory, "identity.json")
        if (!manifest.isFile) return null
        val json = org.json.JSONObject(manifest.readText())
        if (json.optString("fingerprint") != identity.fingerprint) return null
        val files = (0 until json.getJSONArray("apkSha256").length()).map { index ->
            File(directory, if (index == 0) "base.apk" else "split-$index.apk")
        }
        if (files.any { !it.isFile }) return null
        val hashes = files.map { ApkHashing.sha256(it) }
        val recorded = (0 until json.getJSONArray("apkSha256").length()).map { json.getJSONArray("apkSha256").getString(it) }
        if (hashes != recorded) return null
        return Entry(directory, files, identity)
    }

    /** Deletes archived originals for apps that are no longer installed. */
    fun prune(installedPackages: List<String>): Int {
        var removed = 0
        root().listFiles()?.forEach { directory ->
            val manifest = File(directory, "identity.json")
            if (!manifest.isFile) return@forEach
            val pkg = org.json.JSONObject(manifest.readText()).optString("packageName")
            if (pkg.isNotEmpty() && !installedPackages.contains(pkg)) {
                directory.deleteRecursively()
                removed++
            }
        }
        return removed
    }
}
