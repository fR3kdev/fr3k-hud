package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import android.net.Uri
import com.android.apksig.ApkVerifier
import com.mcpintelligence.fr3k.capability.AppIdentity
import com.mcpintelligence.fr3k.capability.sha256Hex
import com.mcpintelligence.fr3k.capability.appmod.*
import com.mcpintelligence.fr3k.appcontrol.analysis.ArchiveBudget
import com.mcpintelligence.fr3k.appcontrol.analysis.ManifestAnalyzer
import com.reandroid.apk.ApkModule
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AppModArtifacts(private val context: Context, private val store: ModificationStore) {
    data class Source(val id: String, val artifact: ArtifactIdentity, val files: List<File>)
    private fun root() = File(context.noBackupFilesDir, "appmod/sources").apply { mkdirs() }
    suspend fun installed(pkg: String): Source {
        val before = AndroidIdentityReader.of(context, pkg)
        val info = context.packageManager.getPackageInfo(pkg, 0).applicationInfo!!
        val originals = listOf(File(info.sourceDir)) + info.splitSourceDirs.orEmpty().map(::File).sortedBy { it.name }
        val staged = capture(originals.map { { it.inputStream() as InputStream } }, SourceKind.INSTALLED, null)
        check(before == staged.artifact.identity && AndroidIdentityReader.of(context, pkg) == before) { "Installed source changed during capture" }
        return staged
    }
    suspend fun imported(uris: List<Uri>): Source {
        require(uris.isNotEmpty() && uris.size <= 32)
        return capture(uris.map { uri -> { requireNotNull(context.contentResolver.openInputStream(uri)) } },
            if (uris.size == 1) SourceKind.IMPORTED_APK else SourceKind.IMPORTED_SPLIT_SET, "Android document picker")
    }
    internal suspend fun capture(inputs: List<() -> InputStream>, kind: SourceKind, origin: String?): Source {
        val id = UUID.randomUUID().toString(); val directory = File(root(), id).apply { check(mkdirs()) }
        try {
            var total = 0L
            val files = inputs.mapIndexed { i, input -> File(directory, "$i.apk").apply {
                input().use { stream -> outputStream().use { output -> val buffer = ByteArray(65536)
                    while (true) { currentCoroutineContext().ensureActive(); val read = stream.read(buffer); if (read < 0) break; total += read
                        require(total <= ArchiveBudget.INPUT) { "APK set exceeds 512 MiB" }; output.write(buffer, 0, read) }
                } }
                ArchiveBudget.verify(this)
            } }
            val metadataFile = File(directory, "metadata.json")
            AppModSandbox.run(context, files, listOf(metadataFile), JSONObject().put("operation", "metadata"))
            val parsed = JSONArray(metadataFile.readText())
            val metadata = files.mapIndexed { index, file ->
                val verification = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(31).build().verify()
                require(verification.isVerified && verification.signerCertificates.size == 1) { "APK signature invalid or unsupported signer set" }
                val entry = parsed.getJSONObject(index)
                Triple(entry, sha256Hex(verification.signerCertificates.single().encoded), entry.getString("split"))
            }
            require(metadata.count { it.third.isEmpty() } == 1 && metadata.map { it.third }.distinct().size == metadata.size) { "One base and distinct split names required" }
            val order = files.indices.sortedWith(compareBy({ metadata[it].third.isNotEmpty() }, { metadata[it].third }))
            val sorted = order.map { files[it] }; val base = metadata[order.first()]
            require(metadata.all { it.first.getString("package") == base.first.getString("package") && it.first.getLong("version") == base.first.getLong("version") && it.second == base.second }) { "Split package/version/signer mismatch" }
            val identity = AppIdentity(base.first.getString("package"), base.first.getLong("version"), base.second, sorted.map(ApkHashing::sha256))
            val artifact = ArtifactIdentity(identity, kind, System.currentTimeMillis(), origin)
            OriginalArchive(context).store(StagedTarget(identity.packageName, sorted.first(), sorted.drop(1), identity.splitSha256, identity))
            store.putRecord("artifact", id, JSONObject().put("artifact", artifact.toJson()).put("files", JSONArray(sorted.map { it.absolutePath })))
            return Source(id, artifact, sorted)
        } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
    }
    fun get(id: String): Source {
        val record = requireNotNull(store.record("artifact", id)) { "Source artifact not found; select it again" }
        val artifact = ArtifactIdentity.fromJson(record.getJSONObject("artifact"))
        val files = record.getJSONArray("files").let { a -> (0 until a.length()).map { File(a.getString(it)) } }
        require(files.all { it.canonicalPath.startsWith(root().canonicalPath + "/") })
        require(files.map(ApkHashing::sha256) == artifact.splitSha256) { "Source bytes changed" }
        return Source(id, artifact, files)
    }
}
