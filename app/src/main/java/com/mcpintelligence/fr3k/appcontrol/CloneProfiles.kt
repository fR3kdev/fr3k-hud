package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import com.mcpintelligence.fr3k.capability.appmod.ArtifactIdentity
import com.mcpintelligence.fr3k.capability.sha256Hex

/** Exact source-byte profiles, never a package-name heuristic for third-party APK cloning. */
object CloneProfiles {
    fun supports(context: Context, artifact: ArtifactIdentity): Boolean {
        if (artifact.splitSha256.size != 1) return false
        val names = when (artifact.packageName) {
            "com.mcpintelligence.fr3k.patchfixture" -> listOf("owned-fixture.apk", "appmod-media-fixture.apk")
            "com.mcpintelligence.fr3k.patchfixturetwo" -> listOf("appmod-gallery-fixture.apk")
            else -> emptyList()
        }
        return names.any { name -> runCatching { context.assets.open("app-control/$name").use { sha256Hex(it.readBytes()) } == artifact.splitSha256.single() }.getOrDefault(false) }
    }
}
