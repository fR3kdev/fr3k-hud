package com.mcpintelligence.fr3k.core.appcontrol

enum class Mode { UNSUPPORTED, PREPARE_AND_RESTART, LIVE }
data class ControlPlan(val mode: Mode, val reason: String)
object CapabilityPlanner {
    /** runtimeLoaded must be an authenticated current observation, not a model assertion. */
    fun plan(recipe: Recipe?, runtimeLoaded: Boolean): ControlPlan = when {
        recipe == null -> ControlPlan(Mode.UNSUPPORTED, "No qualified recipe for this exact target build")
        runtimeLoaded && recipe.supportsLiveToggle ->
            ControlPlan(Mode.LIVE, "Prepared target runtime and compatible live recipe observed")
        else -> ControlPlan(Mode.PREPARE_AND_RESTART, "Target preparation or process restart required")
    }
}
