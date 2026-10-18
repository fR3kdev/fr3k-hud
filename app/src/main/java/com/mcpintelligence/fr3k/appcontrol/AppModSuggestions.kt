package com.mcpintelligence.fr3k.appcontrol

import com.mcpintelligence.fr3k.capability.appmod.*
import org.json.JSONObject

object AppModSuggestions {
    fun generate(report: AnalysisReport): List<ModificationSuggestion> {
        val generated = report.evidence.filter { it.reference is EvidenceReference.Resource || it.evidenceId.contains(":dex:") || it.evidenceId.contains(":helper:") }
            .sortedBy { if (it.reference is EvidenceReference.Resource) 0 else if (it.evidenceId.contains(":helper:")) 1 else 2 }.mapNotNull { e ->
            val data = runCatching { JSONObject(e.feature) }.getOrNull() ?: return@mapNotNull null
            val title = when (val ref = e.reference) {
                is EvidenceReference.Resource -> when (data.optString("type")) {
                    "string" -> "Customize ${data.getString("name")} text"
                    "color" -> "Adjust ${data.getString("name")} contrast or colour"
                    "layout" -> "Adjust visibility of ${data.getString("name")}"
                    else -> return@mapNotNull null
                }
                is EvidenceReference.DexDescriptor -> if (data.has("helpers")) "Extend ${ref.descriptor.substringAfter("->").substringBefore('(').replace(Regex("([a-z])([A-Z])"), "$1 $2")} with a reviewed helper" else "Change ${ref.descriptor.substringAfter("->").substringBefore('(').replace(Regex("([a-z])([A-Z])"), "$1 $2")} default"
                else -> return@mapNotNull null
            }
            val helperOptions = data.optJSONArray("helpers")
            val methodName = (e.reference as? EvidenceReference.DexDescriptor)?.descriptor?.substringAfter("->")?.substringBefore('(').orEmpty()
            val preferredHelper = if (helperOptions == null) null else when {
                methodName.contains("url", true) -> "url.clean"
                methodName.contains("default", true) || methodName.contains("preference", true) -> "preference.persist"
                else -> helperOptions.optString(0).takeIf { it.isNotBlank() }
            }?.takeIf { option -> (0 until helperOptions.length()).any { helperOptions.getString(it) == option } }
            val proposal = if (helperOptions == null) "Replace the exact observed value in a staged copy." else
                "Insert one reviewed helper at the observed return point. URL cleanup removes listed tracking keys; preference persistence remembers the first value per key; HUD sharing opens HUD with returned text. Choose deliberately."
            ModificationSuggestion("${report.analysisId}:edit:${e.evidenceId}", report.analysisId, ProvenanceSource.PURPOSE_BASED, title,
                proposal, "Tailor this app's observed interface or local defaults.", listOf(e.evidenceId), emptyList(),
                ExecutionRoute.APK_PATCH, Readiness.CAN_GENERATE, .85, .8, listOf("Exact source bytes and original value required; static checks do not prove runtime behavior."),
                emptyList(), listOf("Re-signing changes app identity."), "Review and reinstall the hash-verified original archive; private app data is not backed up.", preferredHelper)
        }
        // Old purpose-only ideas have no executable adapter: never present them as runnable.
        val ideas = SuggestionGenerator.generate(report).map { it.copy(suggestionId = "${report.analysisId}:${it.suggestionId}",
            readiness = if (it.readiness == Readiness.CAN_GENERATE) Readiness.NEEDS_ADAPTER else it.readiness,
            executionConfidence = if (it.readiness == Readiness.CAN_GENERATE) .1 else it.executionConfidence) }
        val hooks = report.evidence.filter { it.evidenceId.endsWith("qualified-hook") }.map { e ->
            ModificationSuggestion("${report.analysisId}:owned-hook", report.analysisId, ProvenanceSource.COMPATIBLE_RECIPE,
                "Add reviewed owned-fixture banner controls", "Embed the pinned, reviewed fixture hook module.", "Expose the existing HUD fixture banner-control workflow.",
                listOf(e.evidenceId), emptyList(), ExecutionRoute.APK_PATCH, Readiness.CAN_GENERATE, 1.0, .9,
                listOf("Exact owned-fixture source hash only; configuration must be {}. Cannot combine with other edits or clone this hook."), emptyList(),
                listOf("Re-signing requires a reviewed installation; replacing another signer may lose private app data."), "Reinstall archived original; use the owned fixture control to reverse banner state.")
        }
        return hooks + generated + ideas
    }
}
