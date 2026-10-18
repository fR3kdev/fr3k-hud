package com.mcpintelligence.fr3k.capability.appmod

/**
 * Deterministic, model-free source of PURPOSE_BASED suggestions. Each idea states what changes, why
 * it applies to the analysed artifact, its execution route and readiness, and how to undo it. This
 * is the offline suggestion source — it needs neither network nor a model — and its output is ranked
 * by [SuggestionRanker] against the user's goal before being shown. It deliberately cannot emit a
 * READY or COMPATIBLE_RECIPE suggestion: exact recipe matching is a separate, qualified source.
 */
object SuggestionGenerator {

    fun generate(report: AnalysisReport): List<ModificationSuggestion> {
        val tech = report.technology
        val out = ArrayList<ModificationSuggestion>()

        fun add(
            kind: String,
            title: String,
            change: String,
            benefit: String,
            route: ExecutionRoute,
            readiness: Readiness,
            limits: List<String>,
            recovery: String,
            evidenceIds: List<String> = emptyList(),
        ) {
            out += ModificationSuggestion(
                suggestionId = "purpose.$kind",
                analysisId = report.analysisId,
                provenance = ProvenanceSource.PURPOSE_BASED,
                title = title,
                proposedChange = change,
                expectedBenefit = benefit,
                evidenceIds = evidenceIds,
                requestRefs = emptyList(),
                route = route,
                readiness = readiness,
                relevanceConfidence = 0.5,
                executionConfidence = when (readiness) {
                    Readiness.READY, Readiness.CAN_GENERATE -> 0.7
                    Readiness.ALREADY_AVAILABLE, Readiness.NEEDS_ADAPTER -> 0.3
                    Readiness.UNSUPPORTED -> 0.0
                },
                compatibilityLimits = limits,
                permissions = emptyList(),
                installationConsequences = emptyList(),
                recovery = recovery,
            )
        }

        add(
            kind = "share.integrate",
            title = "Send content to FR3K",
            change = "Expose a supported HUD share/integration action for this app's share target.",
            benefit = "Routes selected content into HUD's existing share and clean-up flows.",
            route = ExecutionRoute.HUD_INTEGRATION,
            readiness = Readiness.CAN_GENERATE,
            limits = listOf("requires the app to publish a share or intent entry point"),
            recovery = "Remove the HUD share action; the target app is unchanged.",
            evidenceIds = evidenceIdOrEmpty(report, "kotlin-runtime"),
        )
        add(
            kind = "prefs.persist",
            title = "Persist per-context preferences",
            change = "Persist a per-context setting instead of the app's single global default.",
            benefit = "Different contexts keep their own behaviour without manual reconfiguration.",
            route = ExecutionRoute.GUIDED_SETTING,
            readiness = Readiness.CAN_GENERATE,
            limits = listOf("only applies where the app already exposes the setting; the value is stored by HUD, not the app"),
            recovery = "Clear the stored HUD preference; nothing in the app changes.",
        )
        if (TechnologyIndicator.WEBVIEW in tech) {
            add(
                kind = "url.clean",
                title = "Clean URLs before they open",
                change = "Route outbound WebView URLs through HUD's URL cleaner.",
                benefit = "Strips tracking parameters before a link opens.",
                route = ExecutionRoute.HUD_INTEGRATION,
                readiness = Readiness.CAN_GENERATE,
                limits = listOf("requires the app to open links in a detectable WebView"),
                recovery = "Disable the URL cleaner; the app's own link handling is untouched.",
                evidenceIds = evidenceIdOrEmpty(report, "webview"),
            )
        }
        if (TechnologyIndicator.NATIVE_LIBRARY in tech) {
            add(
                kind = "native.limit",
                title = "Native runtime present — metadata only",
                change = "Record that this artifact ships native libraries; no native-code rewrite is offered.",
                benefit = "Keeps the feature map honest about what the supported patch language can touch.",
                route = ExecutionRoute.APK_PATCH,
                readiness = Readiness.UNSUPPORTED,
                limits = listOf("native libraries are outside the supported patch language"),
                recovery = "Nothing was changed.",
                evidenceIds = report.evidence.filter { it.evidenceId.startsWith("native-lib-") }.take(1).map { it.evidenceId },
            )
        }
        if (TechnologyIndicator.FLUTTER in tech || TechnologyIndicator.UNITY in tech) {
            add(
                kind = "crossplatform.limit",
                title = "Cross-platform runtime — metadata only",
                change = "Record that this artifact embeds a Flutter/Unity runtime; internals are metadata-level only.",
                benefit = "Prevents over-claiming suggestions for code the analyser cannot reason about.",
                route = ExecutionRoute.APK_PATCH,
                readiness = Readiness.UNSUPPORTED,
                limits = listOf("Flutter/Unity internals are not parsed by the supported analysis"),
                recovery = "Nothing was changed.",
                evidenceIds = listOfNotNull(
                    evidenceIdOrEmpty(report, "flutter"),
                    evidenceIdOrEmpty(report, "unity"),
                ).flatten(),
            )
        }
        if (TechnologyIndicator.OBFUSCATED in tech) {
            add(
                kind = "obfuscated.limit",
                title = "Obfuscated build — adapter required",
                change = "Flag that class names are obfuscated, so exact DEX targeting needs a qualified adapter.",
                benefit = "Keeps DEX-rewrite suggestions honest until exact descriptors are resolved.",
                route = ExecutionRoute.APK_PATCH,
                readiness = Readiness.NEEDS_ADAPTER,
                limits = listOf("obfuscated descriptors prevent reliable DEX target resolution"),
                recovery = "Nothing was changed.",
                evidenceIds = evidenceIdOrEmpty(report, "obfuscation"),
            )
        }
        return out
    }

    private fun evidenceIdOrEmpty(report: AnalysisReport, id: String): List<String> =
        if (report.evidence.any { it.evidenceId == id }) listOf(id) else emptyList()
}
