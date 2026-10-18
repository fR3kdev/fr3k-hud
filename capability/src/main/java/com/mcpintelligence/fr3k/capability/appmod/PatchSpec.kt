package com.mcpintelligence.fr3k.capability.appmod

import org.json.JSONObject

/**
 * A single typed change in the closed patch language. Only validated evidence and implemented
 * operators may appear here; a model may select from these, never invent a new operator.
 */
sealed class PatchChange {
    data class ReplaceResourceString(
        val resourceId: String,
        val expectedOriginal: String,
        val replacement: String,
    ) : PatchChange()

    data class ReplaceResourceColor(
        val resourceId: String,
        val expectedOriginalArgb: Long,
        val replacementArgb: Long,
    ) : PatchChange()

    data class SetLayoutAttribute(
        val resourceId: String,
        val attribute: String,
        val expectedOriginal: String?,
        val replacement: String,
    ) : PatchChange()

    data class ReplaceDexConstant(
        val descriptor: String,
        val expectedOriginal: String,
        val replacement: String,
    ) : PatchChange()

    data class InsertHelperCall(
        val helperTemplateId: String,
        val descriptor: String,
        val instructionOffset: String,
    ) : PatchChange()

    data class ApplyHookTemplate(
        val hookTemplateId: String,
        val configuration: String,
    ) : PatchChange()

    fun toJson(): JSONObject = when (this) {
        is ReplaceResourceString -> JSONObject().put("kind", "resource_string")
            .put("resourceId", resourceId).put("expectedOriginal", expectedOriginal).put("replacement", replacement)
        is ReplaceResourceColor -> JSONObject().put("kind", "resource_color")
            .put("resourceId", resourceId).put("expectedOriginalArgb", expectedOriginalArgb).put("replacementArgb", replacementArgb)
        is SetLayoutAttribute -> JSONObject().put("kind", "layout_attribute")
            .put("resourceId", resourceId).put("attribute", attribute)
            .put("expectedOriginal", expectedOriginal ?: "").put("replacement", replacement)
        is ReplaceDexConstant -> JSONObject().put("kind", "dex_constant")
            .put("descriptor", descriptor).put("expectedOriginal", expectedOriginal).put("replacement", replacement)
        is InsertHelperCall -> JSONObject().put("kind", "helper_call")
            .put("helperTemplateId", helperTemplateId).put("descriptor", descriptor).put("instructionOffset", instructionOffset)
        is ApplyHookTemplate -> JSONObject().put("kind", "hook_template")
            .put("hookTemplateId", hookTemplateId).put("configuration", configuration)
    }

    companion object {
        fun fromJson(json: JSONObject): PatchChange = when (json.getString("kind")) {
            "resource_string" -> ReplaceResourceString(json.getString("resourceId"), json.getString("expectedOriginal"), json.getString("replacement"))
            "resource_color" -> ReplaceResourceColor(json.getString("resourceId"), json.getLong("expectedOriginalArgb"), json.getLong("replacementArgb"))
            "layout_attribute" -> SetLayoutAttribute(json.getString("resourceId"), json.getString("attribute"), json.optString("expectedOriginal").ifEmpty { null }, json.getString("replacement"))
            "dex_constant" -> ReplaceDexConstant(json.getString("descriptor"), json.getString("expectedOriginal"), json.getString("replacement"))
            "helper_call" -> InsertHelperCall(json.getString("helperTemplateId"), json.getString("descriptor"), json.getString("instructionOffset"))
            "hook_template" -> ApplyHookTemplate(json.getString("hookTemplateId"), json.getString("configuration"))
            else -> throw IllegalArgumentException("unknown patch change kind")
        }
    }
}

/** A precondition a change must satisfy at execution time (exact target, expected count, bytes). */
data class PatchPrecondition(
    val conditionId: String,
    val kind: String,
    val target: String,
    val expected: String,
) {
    init { require(conditionId.isNotBlank()) { "conditionId is required" } }

    fun toJson(): JSONObject = JSONObject().apply {
        put("conditionId", conditionId)
        put("kind", kind)
        put("target", target)
        put("expected", expected)
    }

    companion object {
        fun fromJson(json: JSONObject): PatchPrecondition = PatchPrecondition(
            json.getString("conditionId"), json.getString("kind"),
            json.getString("target"), json.getString("expected"),
        )
    }
}

/**
 * Selected changes plus the exact preconditions that make them safe. A spec is bound to one
 * artifact fingerprint; any change to the source identity invalidates it. Dependencies and
 * conflicts are other suggestion/spec ids, so a selection basket can surface them before review.
 */
data class PatchSpec(
    val specId: String,
    val suggestionId: String?,
    val identityFingerprint: String,
    val changes: List<PatchChange>,
    val preconditions: List<PatchPrecondition>,
    val dependencies: List<String>,
    val conflicts: List<String>,
) {
    init {
        require(specId.isNotBlank()) { "specId is required" }
        require(identityFingerprint.matches(Regex("[0-9a-f]{64}"))) { "identityFingerprint must be a SHA-256 hex digest" }
        require(changes.isNotEmpty()) { "a spec must contain at least one change" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("specId", specId)
        put("suggestionId", suggestionId ?: "")
        put("identityFingerprint", identityFingerprint)
        put("changes", org.json.JSONArray().also { arr -> changes.forEach { arr.put(it.toJson()) } })
        put("preconditions", org.json.JSONArray().also { arr -> preconditions.forEach { arr.put(it.toJson()) } })
        put("dependencies", org.json.JSONArray(dependencies))
        put("conflicts", org.json.JSONArray(conflicts))
    }

    companion object {
        private fun stringList(json: JSONObject, key: String): List<String> =
            json.optJSONArray(key)?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()

        fun fromJson(json: JSONObject): PatchSpec {
            require(json.optInt("schemaVersion", 1) == 1) { "Unsupported patch specification version" }
            require(json.getJSONArray("changes").length() in 1..32)
            require(json.getJSONArray("preconditions").length() <= 64)
            val changes = json.getJSONArray("changes").let { arr ->
                (0 until arr.length()).map { PatchChange.fromJson(arr.getJSONObject(it)) }
            }
            val preconditions = json.getJSONArray("preconditions").let { arr ->
                (0 until arr.length()).map { PatchPrecondition.fromJson(arr.getJSONObject(it)) }
            }
            return PatchSpec(
                specId = json.getString("specId"),
                suggestionId = json.optString("suggestionId").ifEmpty { null },
                identityFingerprint = json.getString("identityFingerprint"),
                changes = changes,
                preconditions = preconditions,
                dependencies = stringList(json, "dependencies"),
                conflicts = stringList(json, "conflicts"),
            )
        }
    }
}
