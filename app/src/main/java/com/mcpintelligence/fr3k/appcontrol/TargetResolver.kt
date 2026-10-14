package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import android.content.pm.PackageManager
import com.mcpintelligence.fr3k.capability.AppIdentity
import java.io.File

/** Resolves the user's own installed APK set into a staging directory. Never patches in place. */
class TargetResolver(private val context: Context) {

    fun identityOf(pkg: String): AppIdentity = AndroidIdentityReader.of(context, pkg)

    /** Copies the installed APKs into a private workspace, verifying each copy as it goes. */
    fun stage(pkg: String, workspace: File): StagedTarget {
        val pm = context.packageManager
        val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val application = requireNotNull(info.applicationInfo) { "no application info for $pkg" }
        val sources = listOf(application.sourceDir) + (application.splitSourceDirs?.toList() ?: emptyList()).sorted()
        require(sources.isNotEmpty()) { "package $pkg exposes no APK" }
        val staged = ArrayList<File>()
        val hashes = ArrayList<String>()
        sources.forEachIndexed { index, path ->
            val from = File(path)
            require(from.isFile && from.canRead()) { "cannot read $path" }
            val to = File(workspace, if (index == 0) "base.apk" else "split-$index.apk")
            from.inputStream().use { input -> to.outputStream().use { output -> input.copyTo(output) } }
            val hash = ApkHashing.sha256(to)
            require(hash == ApkHashing.sha256(from)) { "staged copy of $path changed" }
            staged.add(to)
            hashes.add(hash)
        }
        return StagedTarget(pkg, staged.first(), staged.drop(1), hashes, AndroidIdentityReader.of(context, pkg))
    }
}

data class StagedTarget(
    val packageName: String,
    val baseApk: File,
    val splits: List<File>,
    val hashes: List<String>,
    val identity: AppIdentity,
)
