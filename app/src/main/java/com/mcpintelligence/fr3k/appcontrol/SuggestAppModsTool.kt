package com.mcpintelligence.fr3k.appcontrol

import com.mcpintelligence.fr3k.capability.appmod.SuggestionGenerator
import com.mcpintelligence.fr3k.capability.appmod.SuggestionRanker
import com.mcpintelligence.fr3k.core.tools.AgentTool
import com.mcpintelligence.fr3k.core.tools.ToolArgs
import com.mcpintelligence.fr3k.core.tools.ToolContext
import com.mcpintelligence.fr3k.core.tools.ToolResult

/**
 * `suggest_app_mods`: generates local, purpose-based suggestions for a completed analysis, ranks
 * them (exact compatibility first; popularity never overrides incompatibility) and persists them.
 * Offline and model-free. The community-request and compatible-recipe sources are a separate,
 * qualified research step; this tool never emits an unverified READY or COMPATIBLE_RECIPE claim.
 */
class SuggestAppModsTool(private val store: ModificationStore) : AgentTool {
    override val id = "app.control.suggest"
    override val displayName = "Suggest app modifications"
    override val description =
        "Generates local purpose-based suggestions for a completed analysis, ranked by compatibility " +
            "and relevance to the goal. Never modifies the app."
    override val capability = "app.control.suggest"
    override val requiresNetwork = false

    override suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult {
        val analysisId = args.require("analysis_id")
        val goal = args.get("goal")
        val report = store.getAnalysis(analysisId)
            ?: return ToolResult.Failure("no analysis with id $analysisId", "analysis.notfound")
        val suggestions = SuggestionRanker.rank(AppModSuggestions.generate(report) + AppModResearch.cachedSuggestions(store, report), goal)
        store.replaceSuggestions(analysisId, suggestions)
        return ToolResult.Success(
            "Generated ${suggestions.size} suggestions for $analysisId. Top: " +
                suggestions.take(3).joinToString("; ") { "${it.suggestionId}(${it.readiness.name})" },
            mapOf(
                "analysisId" to analysisId,
                "count" to suggestions.size.toString(),
                "top" to suggestions.take(3).joinToString("; ") { "${it.suggestionId}(${it.readiness.name})" },
            ),
        )
    }
}
