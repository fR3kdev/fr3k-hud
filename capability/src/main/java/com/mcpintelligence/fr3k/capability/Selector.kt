package com.mcpintelligence.fr3k.capability

import org.json.JSONArray
import org.json.JSONObject

/** Minimal accessibility-tree node, kept free of Android types so matching is unit-testable. */
data class UiNode(
    val viewId: String? = null,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val password: Boolean = false,
    val clickable: Boolean = false,
    val children: List<UiNode> = emptyList(),
)

data class SelectorFingerprint(
    val viewId: String? = null,
    val className: String? = null,
    val textRegex: String? = null,
    val contentDescRegex: String? = null,
    val minConfidence: Double = 0.8,
) {
    init {
        require(listOfNotNull(viewId, className, textRegex, contentDescRegex).isNotEmpty()) {
            "selector needs at least one criterion"
        }
        require(minConfidence in 0.5..1.0) { "minConfidence out of range" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        viewId?.let { put("viewId", it) }
        className?.let { put("className", it) }
        textRegex?.let { put("textRegex", it) }
        contentDescRegex?.let { put("contentDescRegex", it) }
        put("minConfidence", minConfidence)
    }

    companion object {
        fun fromJson(json: JSONObject): SelectorFingerprint = SelectorFingerprint(
            viewId = json.optString("viewId").ifEmpty { null },
            className = json.optString("className").ifEmpty { null },
            textRegex = json.optString("textRegex").ifEmpty { null },
            contentDescRegex = json.optString("contentDescRegex").ifEmpty { null },
            minConfidence = json.optDouble("minConfidence", 0.8),
        )
    }
}

sealed class MatchOutcome {
    data class Found(val path: List<Int>, val confidence: Double, val node: UiNode) : MatchOutcome()
    data class Ambiguous(val count: Int) : MatchOutcome()
    object NotFound : MatchOutcome()
}

object SelectorMatcher {
    /** Password nodes are never matched by their text or description: that text is user secret. */
    fun score(fingerprint: SelectorFingerprint, node: UiNode): Double {
        val wantedId = fingerprint.viewId
        if (wantedId != null && node.viewId != null) {
            if (node.viewId == wantedId) return 1.0
            if (node.viewId.endsWith("/" + wantedId.substringAfterLast("/"))) return 0.9
        }
        if (node.password) return 0.0
        var score = 0.0
        fingerprint.className?.let { if (it == node.className) score += 0.4 }
        fingerprint.textRegex?.let { pattern ->
            if (node.text != null && Regex(pattern).containsMatchIn(node.text)) score += 0.4
        }
        fingerprint.contentDescRegex?.let { pattern ->
            if (node.contentDescription != null && Regex(pattern).containsMatchIn(node.contentDescription)) score += 0.4
        }
        return score
    }

    fun find(root: UiNode, fingerprint: SelectorFingerprint): MatchOutcome {
        val hits = ArrayList<Triple<Double, List<Int>, UiNode>>()
        walk(root, emptyList(), fingerprint, hits)
        val confident = hits.filter { it.first >= fingerprint.minConfidence }
        return when {
            confident.isEmpty() -> MatchOutcome.NotFound
            confident.size > 1 -> MatchOutcome.Ambiguous(confident.size)
            else -> MatchOutcome.Found(confident[0].second, confident[0].first, confident[0].third)
        }
    }

    private fun walk(
        node: UiNode,
        path: List<Int>,
        fingerprint: SelectorFingerprint,
        hits: MutableList<Triple<Double, List<Int>, UiNode>>,
    ) {
        val score = score(fingerprint, node)
        if (score > 0.0) hits.add(Triple(score, path, node))
        node.children.forEachIndexed { index, child -> walk(child, path + index, fingerprint, hits) }
    }

    fun toJson(root: UiNode): JSONObject = JSONObject().apply {
        root.viewId?.let { put("viewId", it) }
        root.className?.let { put("className", it) }
        root.text?.let { put("text", it) }
        root.contentDescription?.let { put("contentDescription", it) }
        put("password", root.password)
        put("clickable", root.clickable)
        put("children", JSONArray().also { arr -> root.children.forEach { arr.put(toJson(it)) } })
    }
}
