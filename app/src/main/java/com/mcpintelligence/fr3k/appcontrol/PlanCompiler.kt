package com.mcpintelligence.fr3k.appcontrol

import com.mcpintelligence.fr3k.capability.PlanGraph
import com.mcpintelligence.fr3k.capability.PlanRefusedException
import com.mcpintelligence.fr3k.capability.PlanStep
import com.mcpintelligence.fr3k.capability.RegistryStore

/**
 * Deterministic goal -> plan compiler. It maps a goal onto capabilities that already exist in the
 * registry for the exact installed builds; it can never invent an action, and it refuses rather
 * than guessing. An LLM may call this, but nothing here depends on a model.
 */
class PlanCompiler(private val host: CapabilityHost) {

    data class Result(
        val plan: PlanGraph?,
        val refusal: String?,
        val candidates: List<String>,
        val score: Double = 0.0,
    )

    private val stopWords = setOf(
        "the", "a", "an", "to", "of", "for", "my", "me", "please", "and", "in", "on", "it", "this", "that", "with",
    )

    fun compile(goal: String): Result {
        require(goal.isNotBlank()) { "goal is required" }
        val registry = host.registry(refresh = true)
        val installed = host.installed()
        val phrases = CapabilityCatalogue.phrases()
        val normalized = normalize(goal)

        val scored = registry.all().map { entry ->
            val id = entry.descriptor.id
            val phraseHit = phrases[id]?.any { normalized.contains(normalize(it)) } ?: false
            val tokenScore = tokenOverlap(goal, entry.descriptor.summary + " " + id)
            val score = if (phraseHit) 10.0 + tokenScore else tokenScore
            Triple(id, score, entry.descriptor.identity)
        }.sortedByDescending { it.second }

        val top = scored.firstOrNull()
            ?: return Result(null, "no capabilities are registered on this device", emptyList())
        if (top.second < 3.0) {
            return Result(
                null,
                "no registered capability matches '$goal'; the closest were " +
                    scored.take(3).joinToString { "${it.first} (${"%.1f".format(it.second)})" },
                scored.take(3).map { it.first },
                top.second,
            )
        }
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && runnerUp.second >= top.second) {
            return Result(
                null,
                "goal is ambiguous between ${top.first} and ${runnerUp.first}; say which you want",
                scored.take(3).map { it.first },
                top.second,
            )
        }
        val descriptor = registry.find(top.first, top.third)
            ?: return Result(null, "${top.first} is registered but not for ${top.third.label}", listOf(top.first))
        return try {
            val plan = PlanGraph.build(goal, listOf(PlanStep(descriptor.id, descriptor.identity)), registry, installed)
            Result(plan, null, listOf(descriptor.id), top.second)
        } catch (refused: PlanRefusedException) {
            Result(null, refused.message, listOf(descriptor.id), top.second)
        }
    }

    private fun tokenOverlap(goal: String, haystack: String): Double {
        val goalTokens = tokens(goal)
        if (goalTokens.isEmpty()) return 0.0
        val hayTokens = tokens(haystack).toSet()
        return goalTokens.count { hayTokens.contains(it) }.toDouble()
    }

    private fun tokens(value: String): List<String> =
        value.lowercase().split(Regex("[^a-z0-9_]+"))
            .filter { it.length > 2 && !stopWords.contains(it) }
            .map { if (it.endsWith("s")) it.dropLast(1) else it }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
