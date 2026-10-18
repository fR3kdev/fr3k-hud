package com.mcpintelligence.fr3k.capability.appmod

import org.json.JSONObject

/**
 * Classification of a single finding. [OBSERVED] is a fact read from the artifact; [INFERRED] is
 * a purpose HUD deduced; [UNKNOWN] is a behaviour HUD could not determine. Obfuscated names,
 * missing strings or a decompilation failure must never be reported as a proof of absence.
 */
enum class EvidenceKind { OBSERVED, INFERRED, UNKNOWN }

/** Exact location a finding points at, so a suggestion can be traced to a reproducible reference. */
sealed class EvidenceReference {
    data class Resource(val resourceId: String) : EvidenceReference()
    data class ManifestComponent(val componentName: String) : EvidenceReference()
    data class DexDescriptor(val descriptor: String, val instructionOffset: String?) : EvidenceReference()
    data class ApiUse(val api: String) : EvidenceReference()
    data class Permission(val name: String) : EvidenceReference()
    object None : EvidenceReference()

    fun toJson(): JSONObject = when (this) {
        is Resource -> JSONObject().put("kind", "resource").put("resourceId", resourceId)
        is ManifestComponent -> JSONObject().put("kind", "component").put("componentName", componentName)
        is DexDescriptor -> JSONObject().put("kind", "dex").put("descriptor", descriptor)
            .put("instructionOffset", instructionOffset ?: "")
        is ApiUse -> JSONObject().put("kind", "api").put("api", api)
        is Permission -> JSONObject().put("kind", "permission").put("name", name)
        None -> JSONObject().put("kind", "none")
    }

    companion object {
        fun fromJson(json: JSONObject): EvidenceReference = when (json.getString("kind")) {
            "resource" -> Resource(json.getString("resourceId"))
            "component" -> ManifestComponent(json.getString("componentName"))
            "dex" -> DexDescriptor(json.getString("descriptor"), json.optString("instructionOffset").ifEmpty { null })
            "api" -> ApiUse(json.getString("api"))
            "permission" -> Permission(json.getString("name"))
            "none" -> None
            else -> throw IllegalArgumentException("unknown evidence reference kind")
        }
    }
}

/** One finding with a classification and an exact reference into the artifact. */
data class FeatureEvidence(
    val evidenceId: String,
    val kind: EvidenceKind,
    val feature: String,
    val reference: EvidenceReference,
) {
    init { require(evidenceId.isNotBlank()) { "evidenceId is required" } }

    fun toJson(): JSONObject = JSONObject().apply {
        put("evidenceId", evidenceId)
        put("kind", kind.name)
        put("feature", feature)
        put("reference", reference.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject): FeatureEvidence = FeatureEvidence(
            json.getString("evidenceId"),
            EvidenceKind.valueOf(json.getString("kind")),
            json.getString("feature"),
            EvidenceReference.fromJson(json.getJSONObject("reference")),
        )
    }
}

/** Broad technology indicators inferred from the artifact, each independently observable. */
enum class TechnologyIndicator {
    JAVA_KOTLIN, COMPOSE, WEBVIEW, FLUTTER, UNITY, NATIVE_LIBRARY, OBFUSCATED, MULTIDEX, SPLIT_SET,
}

/**
 * The result of analysing one artifact: its identity, technology indicators, an ordered list of
 * findings and any coverage limits. [completed] is false when a budget was hit and the report is
 * explicitly partial; [limits] names what was not covered so a suggestion never over-claims.
 */
data class AnalysisReport(
    val analysisId: String,
    val artifact: ArtifactIdentity,
    val analyzedAtMillis: Long,
    val technology: Set<TechnologyIndicator>,
    val evidence: List<FeatureEvidence>,
    val completed: Boolean,
    val limits: List<String>,
) {
    init {
        require(analysisId.isNotBlank()) { "analysisId is required" }
        require(analyzedAtMillis > 0) { "analyzedAtMillis must be positive" }
        if (!completed) require(limits.isNotEmpty()) { "a partial report must name its limits" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("analysisId", analysisId)
        put("artifact", artifact.toJson())
        put("analyzedAtMillis", analyzedAtMillis)
        put("technology", org.json.JSONArray(technology.map { it.name }))
        put("evidence", org.json.JSONArray().also { arr -> evidence.forEach { arr.put(it.toJson()) } })
        put("completed", completed)
        put("limits", org.json.JSONArray(limits))
    }

    companion object {
        fun fromJson(json: JSONObject): AnalysisReport {
            val tech = json.getJSONArray("technology").let { arr ->
                (0 until arr.length()).map { TechnologyIndicator.valueOf(arr.getString(it)) }.toSet()
            }
            val evidence = json.getJSONArray("evidence").let { arr ->
                (0 until arr.length()).map { FeatureEvidence.fromJson(arr.getJSONObject(it)) }
            }
            val limits = json.getJSONArray("limits").let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
            return AnalysisReport(
                analysisId = json.getString("analysisId"),
                artifact = ArtifactIdentity.fromJson(json.getJSONObject("artifact")),
                analyzedAtMillis = json.getLong("analyzedAtMillis"),
                technology = tech,
                evidence = evidence,
                completed = json.getBoolean("completed"),
                limits = limits,
            )
        }
    }
}
