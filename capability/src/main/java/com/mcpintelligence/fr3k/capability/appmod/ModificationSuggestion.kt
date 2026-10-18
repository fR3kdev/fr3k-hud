package com.mcpintelligence.fr3k.capability.appmod

import org.json.JSONObject

/** Where a suggestion came from. Popularity must never override an incompatibility. */
enum class ProvenanceSource { COMPATIBLE_RECIPE, COMMUNITY_REQUEST, PURPOSE_BASED }

/** How a suggestion would be executed. */
enum class ExecutionRoute { APK_PATCH, HUD_INTEGRATION, GUIDED_SETTING }

/**
 * Whether HUD can execute the suggestion right now. [ALREADY_AVAILABLE] means the app already does
 * it; [UNSUPPORTED] and [NEEDS_ADAPTER] are honest labels, never silent fallbacks.
 */
enum class Readiness { READY, CAN_GENERATE, NEEDS_ADAPTER, ALREADY_AVAILABLE, UNSUPPORTED }

/**
 * One candidate improvement with separately labelled provenance, applicability, route and two
 * confidences: how relevant it is to the user's goal, and how confident HUD is that it can be
 * executed. Relevance and execution confidence are deliberately distinct fields.
 */
data class ModificationSuggestion(
    val suggestionId: String,
    val analysisId: String,
    val provenance: ProvenanceSource,
    val title: String,
    val proposedChange: String,
    val expectedBenefit: String,
    val evidenceIds: List<String>,
    val requestRefs: List<String>,
    val route: ExecutionRoute,
    val readiness: Readiness,
    val relevanceConfidence: Double,
    val executionConfidence: Double,
    val compatibilityLimits: List<String>,
    val permissions: List<String>,
    val installationConsequences: List<String>,
    val recovery: String,
    val suggestedReplacement: String? = null,
) {
    init {
        require(suggestionId.isNotBlank()) { "suggestionId is required" }
        require(analysisId.isNotBlank()) { "analysisId is required" }
        require(relevanceConfidence in 0.0..1.0) { "relevanceConfidence must be in 0..1" }
        require(executionConfidence in 0.0..1.0) { "executionConfidence must be in 0..1" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("suggestionId", suggestionId)
        put("analysisId", analysisId)
        put("provenance", provenance.name)
        put("title", title)
        put("proposedChange", proposedChange)
        put("expectedBenefit", expectedBenefit)
        put("evidenceIds", org.json.JSONArray(evidenceIds))
        put("requestRefs", org.json.JSONArray(requestRefs))
        put("route", route.name)
        put("readiness", readiness.name)
        put("relevanceConfidence", relevanceConfidence)
        put("executionConfidence", executionConfidence)
        put("compatibilityLimits", org.json.JSONArray(compatibilityLimits))
        put("permissions", org.json.JSONArray(permissions))
        put("installationConsequences", org.json.JSONArray(installationConsequences))
        put("recovery", recovery)
        put("suggestedReplacement", suggestedReplacement ?: JSONObject.NULL)
    }

    companion object {
        private fun stringList(json: JSONObject, key: String): List<String> =
            json.optJSONArray(key)?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()

        fun fromJson(json: JSONObject): ModificationSuggestion = ModificationSuggestion(
            suggestionId = json.getString("suggestionId"),
            analysisId = json.getString("analysisId"),
            provenance = ProvenanceSource.valueOf(json.getString("provenance")),
            title = json.optString("title"),
            proposedChange = json.optString("proposedChange"),
            expectedBenefit = json.optString("expectedBenefit"),
            evidenceIds = stringList(json, "evidenceIds"),
            requestRefs = stringList(json, "requestRefs"),
            route = ExecutionRoute.valueOf(json.getString("route")),
            readiness = Readiness.valueOf(json.getString("readiness")),
            relevanceConfidence = json.getDouble("relevanceConfidence"),
            executionConfidence = json.getDouble("executionConfidence"),
            compatibilityLimits = stringList(json, "compatibilityLimits"),
            permissions = stringList(json, "permissions"),
            installationConsequences = stringList(json, "installationConsequences"),
            recovery = json.optString("recovery"),
            suggestedReplacement = if (json.isNull("suggestedReplacement")) null else json.getString("suggestedReplacement"),
        )
    }
}
