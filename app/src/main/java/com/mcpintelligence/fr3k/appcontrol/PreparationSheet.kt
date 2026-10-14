package com.mcpintelligence.fr3k.appcontrol

import org.json.JSONArray
import org.json.JSONObject

/** Everything a user must see before an APK is replaced, and everything evidence needs afterwards. */
data class PreparationSheet(
    val packageName: String,
    val appFingerprint: String,
    val engineId: String,
    val engineVersion: String,
    val patchIds: List<String>,
    val patchBundleDigest: String?,
    val sourceSha256: String,
    val outputSha256: String,
    val archivePath: String,
    val archiveSha256: String,
    val warnings: List<String>,
    val acknowledged: Boolean,
) {
    init {
        require(packageName.isNotBlank())
        require(sourceSha256.matches(Regex("[0-9a-f]{64}")))
        require(outputSha256.matches(Regex("[0-9a-f]{64}")))
        require(archiveSha256.matches(Regex("[0-9a-f]{64}")))
        require(engineId.isNotBlank())
    }

    /** A sheet describes what will happen; the approval is what makes it installable. */
    val installable: Boolean get() = warnings.isEmpty() || acknowledged

    fun requireInstallable() {
        require(installable) { "this build has ${warnings.size} warning(s) that the operator has not acknowledged" }
    }

    fun acknowledge(): PreparationSheet = copy(acknowledged = true)

    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("appFingerprint", appFingerprint)
        put("engineId", engineId)
        put("engineVersion", engineVersion)
        put("patchIds", JSONArray(patchIds))
        put("patchBundleDigest", patchBundleDigest ?: "")
        put("sourceSha256", sourceSha256)
        put("outputSha256", outputSha256)
        put("archivePath", archivePath)
        put("archiveSha256", archiveSha256)
        put("warnings", JSONArray(warnings))
        put("acknowledged", acknowledged)
    }
}
