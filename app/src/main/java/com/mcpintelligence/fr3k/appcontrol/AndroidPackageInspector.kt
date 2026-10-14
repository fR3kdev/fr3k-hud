package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import android.content.pm.PackageManager
import com.mcpintelligence.fr3k.core.appcontrol.TargetIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Reads installed APKs only. Never accepts a file path or reads private application data. */
class AndroidPackageInspector(private val context: Context) {
    suspend fun inspect(pkg: String): TargetIdentity = withContext(Dispatchers.IO) {
        require(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        val pm = context.packageManager
        val before = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val signatures = requireNotNull(before.signingInfo).apkContentsSigners
        require(signatures.size == 1) { "Multi-signer targets are not yet supported" }
        val info = requireNotNull(before.applicationInfo)
        val paths = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList()).sortedBy { File(it).name }
        val files = paths.map(::File)
        require(files.all { it.isFile && it.canRead() }) { "Installed APK is not readable" }
        val bytes = files.sumOf { it.length() }
        require(bytes in 1..536_870_912L) { "Selected APK set exceeds the 512 MiB inspection budget" }
        val hashes = files.map { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= 536_870_912L) { "APK grew beyond inspection budget" }
                    digest.update(buffer, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val after = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        require(before.lastUpdateTime == after.lastUpdateTime &&
            before.longVersionCode == after.longVersionCode &&
            info.sourceDir == after.applicationInfo?.sourceDir &&
            info.splitSourceDirs.contentEquals(after.applicationInfo?.splitSourceDirs) &&
            signatures.contentEquals(after.signingInfo?.apkContentsSigners)) { "Package changed during inspection" }
        val signer = MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        TargetIdentity(pkg, before.longVersionCode, signer, hashes)
    }
}
