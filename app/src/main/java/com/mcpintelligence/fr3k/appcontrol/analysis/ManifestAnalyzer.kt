package com.mcpintelligence.fr3k.appcontrol.analysis

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File

/** Binary AndroidManifest.xml + declared components/permissions extracted via the pinned ARSCLib. */
data class ManifestSummary(
    val packageName: String?,
    val versionCode: Long?,
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val mainActivity: String?,
    val applicationClass: String?,
    val permissions: List<String>,
    val activities: List<String>,
    val services: List<String>,
    val receivers: List<String>,
    val providers: List<String>,
) {
    val componentCount: Int get() = activities.size + services.size + receivers.size + providers.size
}

/**
 * Phone-only binary-manifest reader. Parses the APK's AndroidManifest.xml with ARSCLib (no
 * decompilation, no build tools) and returns the declared package metadata, permissions and
 * components. Pure JVM, so it runs identically on-device and in unit tests.
 */
object ManifestAnalyzer {

    fun parse(apk: File): ManifestSummary {
        return ApkAccess.open(apk).use { parse(it.module) }
    }

    fun parse(module: ApkModule): ManifestSummary {
        val manifest = module.androidManifest
        fun names(tag: String): List<String> =
            manifest.listApplicationElementsByTag(tag)
                .mapNotNull { AndroidManifestBlock.getAndroidNameValue(it)?.takeIf { n -> n.isNotBlank() } }
        return ManifestSummary(
            packageName = manifest.packageName?.takeIf { it.isNotBlank() },
            versionCode = manifest.versionCode?.let { low ->
                val major = manifest.documentElement.attributes.asSequence().singleOrNull { it.nameId == 16844150 }?.data ?: 0
                require(major >= 0) { "Version code exceeds supported positive Long range" }
                (major.toLong() shl 32) or low.toLong().and(0xffffffffL)
            },
            versionName = manifest.versionName,
            minSdk = manifest.minSdkVersion,
            targetSdk = manifest.targetSdkVersion,
            mainActivity = manifest.mainActivityClassName?.takeIf { it.isNotBlank() },
            applicationClass = manifest.applicationClassName?.takeIf { it.isNotBlank() },
            permissions = manifest.usesPermissions,
            activities = names("activity"),
            services = names("service"),
            receivers = names("receiver"),
            providers = names("provider"),
        )
    }
}
