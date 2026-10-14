package com.mcpintelligence.fr3k.capability

data class Expectation(val kind: Kind, val argument: String) {
    enum class Kind { SELECTOR_PRESENT, SELECTOR_ABSENT, VERSION_CODE, FILE_SHA256 }
}

data class PlanStep(
    val capabilityId: String,
    val identity: AppIdentity,
    val parameters: Map<String, String> = emptyMap(),
    val expect: List<Expectation> = emptyList(),
) {
    fun stepsFor(descriptor: CapabilityDescriptor): List<Step> = descriptor.instantiate(parameters)
}

class PlanRefusedException(failures: List<String>) :
    IllegalArgumentException("plan contains steps that cannot be verified:\n" + failures.joinToString("\n"))

/** A plan may only reference capabilities that exist in the registry for an exact app identity. */
data class PlanGraph(val goal: String, val steps: List<PlanStep>, val warnings: List<String> = emptyList()) {

    val riskSummary: String
        get() = steps.map { it.capabilityId }.distinct().joinToString(", ")

    companion object {
        fun build(
            goal: String,
            requested: List<PlanStep>,
            registry: RegistryStore,
            installed: Map<String, AppIdentity>,
        ): PlanGraph {
            require(goal.isNotBlank()) { "goal is required" }
            val failures = ArrayList<String>()
            val warnings = ArrayList<String>()
            requested.forEach { step ->
                val observed = installed[step.identity.packageName]
                if (observed == null) {
                    failures.add("- ${step.identity.packageName} is not installed; no capability can target it")
                } else if (observed.fingerprint != step.identity.fingerprint) {
                    failures.add(
                        "- ${step.capabilityId}: expected ${step.identity.label} but observed ${observed.label}"
                    )
                } else if (registry.find(step.capabilityId, step.identity) == null) {
                    val known = registry.listFor(step.identity).joinToString(", ") { it.id }.ifEmpty { "none" }
                    failures.add("- ${step.capabilityId} is not registered for ${step.identity.label} (known: $known)")
                }
            }
            if (failures.isNotEmpty()) throw PlanRefusedException(failures)
            return PlanGraph(goal, requested, warnings)
        }
    }
}

object PlanRenderer {
    fun render(plan: PlanGraph, registry: RegistryStore): String = buildString {
        appendLine("GOAL: ${plan.goal}")
        appendLine("STEPS: ${plan.steps.size}")
        plan.steps.forEachIndexed { index, step ->
            val descriptor = registry.find(step.capabilityId, step.identity)
            appendLine("${index + 1}. ${descriptor?.summary ?: step.capabilityId}")
            appendLine("   app: ${step.identity.packageName} @${step.identity.versionCode} (${step.identity.fingerprint.take(12)})")
            appendLine("   risk: ${descriptor?.risk ?: RiskClass.OBSERVE}")
            if (step.parameters.isNotEmpty()) appendLine("   parameters: " + step.parameters.toSortedMap().entries.joinToString { "${it.key}=${it.value}" })
            appendLine("   changes: " + (descriptor?.risk?.name?.lowercase() ?: "unknown"))
            appendLine("   undo: " + undoDescription(descriptor))
        }
        plan.warnings.forEach { appendLine("WARNING: $it") }
    }

    fun undoDescription(descriptor: CapabilityDescriptor?): String = when (descriptor?.risk) {
        RiskClass.REINSTALL -> "reinstall the archived original APK for this app"
        RiskClass.PRIVILEGED -> "restore the previous per-app policy value"
        RiskClass.REVERSIBLE_WRITE -> "reverse the recorded step against the same app build"
        else -> "nothing to undo"
    }
}
