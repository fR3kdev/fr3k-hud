package com.mcpintelligence.fr3k.capability.appmod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionRankerTest {

    private fun suggestion(
        id: String,
        readiness: Readiness,
        relevance: Double = 0.5,
        provenance: ProvenanceSource = ProvenanceSource.PURPOSE_BASED,
        requests: Int = 0,
        consequences: Int = 0,
        title: String = "generic",
        proposed: String = "generic change",
        benefit: String = "generic benefit",
    ) = ModificationSuggestion(
        suggestionId = id,
        analysisId = "a1",
        provenance = provenance,
        title = title,
        proposedChange = proposed,
        expectedBenefit = benefit,
        evidenceIds = emptyList(),
        requestRefs = (0 until requests).map { "https://example.com/issue/$it" },
        route = ExecutionRoute.APK_PATCH,
        readiness = readiness,
        relevanceConfidence = relevance,
        executionConfidence = 0.5,
        compatibilityLimits = List(consequences) { "limit" },
        permissions = emptyList(),
        installationConsequences = List(consequences) { "consequence" },
        recovery = "reinstall original",
    )

    @Test
    fun popularityNeverOverridesIncompatibility() {
        val executable = suggestion("exe", Readiness.READY, relevance = 0.1, requests = 0)
        val popularButUnsupported = suggestion("unsup", Readiness.UNSUPPORTED, relevance = 1.0, requests = 50)
        val ranked = SuggestionRanker.rank(listOf(popularButUnsupported, executable))
        assertEquals(listOf("exe", "unsup"), ranked.map { it.suggestionId })
    }

    @Test
    fun readinessTiersOrderFirst() {
        val canGenerate = suggestion("gen", Readiness.CAN_GENERATE, relevance = 0.0)
        val already = suggestion("already", Readiness.ALREADY_AVAILABLE, relevance = 1.0)
        val ready = suggestion("ready", Readiness.READY, relevance = 0.0)
        val ranked = SuggestionRanker.rank(listOf(already, canGenerate, ready))
        assertEquals(listOf("ready", "gen", "already"), ranked.map { it.suggestionId })
    }

    @Test
    fun relevanceBreaksTiesWithinSameReadiness() {
        val low = suggestion("low", Readiness.READY, relevance = 0.2)
        val high = suggestion("high", Readiness.READY, relevance = 0.9)
        val ranked = SuggestionRanker.rank(listOf(low, high))
        assertEquals(listOf("high", "low"), ranked.map { it.suggestionId })
    }

    @Test
    fun goalPromotesMatchingSuggestions() {
        val unrelated = suggestion("unrelated", Readiness.READY, relevance = 0.5, title = "notification icons")
        val onGoal = suggestion(
            "onGoal", Readiness.READY, relevance = 0.5,
            title = "playback speed", proposed = "persist playback speed control",
        )
        val ranked = SuggestionRanker.rank(listOf(unrelated, onGoal), goal = "keep playback speed controls visible")
        assertEquals("onGoal", ranked.first().suggestionId)
    }

    @Test
    fun deterministicForEqualInput() {
        val a = suggestion("a", Readiness.READY, relevance = 0.5)
        val b = suggestion("b", Readiness.READY, relevance = 0.5)
        val first = SuggestionRanker.rank(listOf(a, b)).map { it.suggestionId }
        val second = SuggestionRanker.rank(listOf(b, a)).map { it.suggestionId }
        assertTrue(first == second)
    }
}
