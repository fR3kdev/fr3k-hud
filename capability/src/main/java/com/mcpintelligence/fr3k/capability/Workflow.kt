package com.mcpintelligence.fr3k.capability

sealed class Trigger {
    object Manual : Trigger()
    data class HudButton(val label: String) : Trigger()
    data class Deeplink(val uri: String) : Trigger()
    data class Broadcast(val action: String) : Trigger()
    data class McpTool(val name: String) : Trigger()
    data class Schedule(val cron: String) : Trigger()
}

data class WorkflowStep(
    val capabilityId: String,
    val identity: AppIdentity,
    val parameters: Map<String, String> = emptyMap(),
)

data class WorkflowSpec(
    val id: String,
    val name: String,
    val version: Int,
    val steps: List<WorkflowStep>,
    val triggers: List<Trigger> = listOf(Trigger.Manual),
    val stopBeforeIrreversible: Boolean = true,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_.-]{2,63}"))) { "invalid workflow id" }
        require(steps.isNotEmpty()) { "workflow needs at least one step" }
        require(version >= 1)
    }
}

data class BoundStep(val workflowStep: WorkflowStep, val descriptor: CapabilityDescriptor, val steps: List<Step>)

class WorkflowBindException(message: String) : IllegalArgumentException(message)

object WorkflowBinder {
    /** Binds a workflow to the exact installed builds; a fingerprint gap reports both sides. */
    fun bind(spec: WorkflowSpec, registry: RegistryStore, installed: Map<String, AppIdentity>): List<BoundStep> {
        val bound = ArrayList<BoundStep>()
        val failures = ArrayList<String>()
        spec.steps.forEach { step ->
            val observed = installed[step.identity.packageName]
            val descriptor = registry.find(step.capabilityId, step.identity)
            when {
                observed == null -> failures.add("${step.capabilityId}: ${step.identity.packageName} is not installed")
                observed.fingerprint != step.identity.fingerprint ->
                    failures.add("${step.capabilityId}: expected ${step.identity.label} but observed ${observed.label}")
                descriptor == null ->
                    failures.add("${step.capabilityId}: not registered for ${step.identity.label}")
                else -> bound.add(BoundStep(step, descriptor, step.parameters.let { descriptor.instantiate(it) }))
            }
        }
        if (failures.isNotEmpty()) throw WorkflowBindException("workflow '${spec.id}' cannot bind:\n" + failures.joinToString("\n"))
        return bound
    }
}
