package com.mcpintelligence.fr3k.capability.appmod

/**
 * Deterministic, model-free ranking of suggestions. Order of precedence, most significant first:
 *
 *  1. Executable first, always. A suggestion that is not READY/CAN_GENERATE is ranked after every
 *     executable one regardless of how many people asked for it — popularity must never override
 *     an incompatibility.
 *  2. Readiness tier (READY > CAN_GENERATE > ALREADY_AVAILABLE > NEEDS_ADAPTER > UNSUPPORTED).
 *  3. Relevance confidence.
 *  4. User-goal fit (token overlap against the supplied goal, falling back to provenance weight).
 *  5. Supporting requests (more community/request references first).
 *  6. Effort/risk (fewer limits, permissions and installation consequences first).
 *  7. Suggestion id (stable tiebreak so equal inputs always sort deterministically).
 */
object SuggestionRanker {

    private val readinessRank = mapOf(
        Readiness.READY to 0,
        Readiness.CAN_GENERATE to 1,
        Readiness.ALREADY_AVAILABLE to 2,
        Readiness.NEEDS_ADAPTER to 3,
        Readiness.UNSUPPORTED to 4,
    )

    private val stopWords = setOf(
        "the", "a", "an", "to", "of", "for", "my", "me", "please", "and", "in", "on", "it", "this",
        "that", "with", "app", "would", "should", "when", "during", "keep", "control",
    )

    fun rank(suggestions: List<ModificationSuggestion>, goal: String? = null): List<ModificationSuggestion> {
        val goalTokens = goal?.let(::tokens) ?: emptySet()
        return suggestions.sortedWith(
            compareBy(
                { if (executable(it)) 0 else 1 },
                { readinessRank[it.readiness] ?: Int.MAX_VALUE },
                { -it.relevanceConfidence },
                { -goalFit(it, goalTokens) },
                { -it.requestRefs.size },
                { effortRisk(it) },
                { it.suggestionId },
            ),
        )
    }

    private fun executable(s: ModificationSuggestion) =
        s.readiness == Readiness.READY || s.readiness == Readiness.CAN_GENERATE

    private fun goalFit(s: ModificationSuggestion, goalTokens: Set<String>): Double {
        if (goalTokens.isEmpty()) {
            val provenanceWeight = when (s.provenance) {
                ProvenanceSource.COMPATIBLE_RECIPE -> 3.0
                ProvenanceSource.COMMUNITY_REQUEST -> 2.0
                ProvenanceSource.PURPOSE_BASED -> 1.0
            }
            return provenanceWeight + s.executionConfidence
        }
        val haystack = tokens(s.title + " " + s.proposedChange + " " + s.expectedBenefit)
        return goalTokens.count { it in haystack }.toDouble()
    }

    private fun effortRisk(s: ModificationSuggestion): Int =
        s.installationConsequences.size + s.compatibilityLimits.size + s.permissions.size

    private fun tokens(value: String): Set<String> =
        value.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in stopWords }
            .toSet()
}
