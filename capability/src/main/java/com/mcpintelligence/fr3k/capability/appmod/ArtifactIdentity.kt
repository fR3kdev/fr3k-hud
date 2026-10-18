package com.mcpintelligence.fr3k.capability.appmod

import com.mcpintelligence.fr3k.capability.AppIdentity
import org.json.JSONObject

/** How an APK set entered the app-modification pipeline. */
enum class SourceKind { INSTALLED, IMPORTED_APK, IMPORTED_SPLIT_SET }

/**
 * Immutable identity of one analysed APK set plus the provenance of how its bytes were obtained.
 *
 * The build identity (package, version, signer, split hashes) is carried by [AppIdentity]; this
 * type adds where the bytes came from so a suggestion, patch spec or job can trace back to an
 * exact, reproducible source. Imported artifacts must record their origin; installed ones carry
 * no origin because the source is the device's own package set.
 */
data class ArtifactIdentity(
    val identity: AppIdentity,
    val sourceKind: SourceKind,
    val capturedAtMillis: Long,
    val importedOrigin: String?,
) {
    init {
        require(capturedAtMillis > 0) { "capturedAtMillis must be positive" }
        if (sourceKind != SourceKind.INSTALLED) {
            require(!importedOrigin.isNullOrBlank()) { "imported artifacts must record their origin" }
        }
    }

    val packageName: String get() = identity.packageName
    val versionCode: Long get() = identity.versionCode
    val signerSha256: String get() = identity.signerSha256
    val splitSha256: List<String> get() = identity.splitSha256
    val fingerprint: String get() = identity.fingerprint
    val label: String get() = identity.label

    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", identity.packageName)
        put("versionCode", identity.versionCode)
        put("signerSha256", identity.signerSha256)
        put("splitSha256", org.json.JSONArray(identity.splitSha256))
        put("sourceKind", sourceKind.name)
        put("capturedAtMillis", capturedAtMillis)
        put("importedOrigin", importedOrigin ?: "")
    }

    companion object {
        fun fromJson(json: JSONObject): ArtifactIdentity {
            val splits = json.getJSONArray("splitSha256").let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
            return ArtifactIdentity(
                identity = AppIdentity(
                    json.getString("packageName"),
                    json.getLong("versionCode"),
                    json.getString("signerSha256"),
                    splits,
                ),
                sourceKind = SourceKind.valueOf(json.getString("sourceKind")),
                capturedAtMillis = json.getLong("capturedAtMillis"),
                importedOrigin = json.optString("importedOrigin").ifEmpty { null },
            )
        }
    }
}
