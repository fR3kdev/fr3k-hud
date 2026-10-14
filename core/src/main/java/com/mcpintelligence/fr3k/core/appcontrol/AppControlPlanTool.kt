package com.mcpintelligence.fr3k.core.appcontrol

import com.mcpintelligence.fr3k.core.tools.*

/** Conservative catalogue query, not a mutation or a privileged executor. */
class AppControlPlanTool : AgentTool {
    override val id = "app.control.plan"
    override val displayName = "Plan app modification"
    override val description = "Reports qualified app-control support and prerequisites; never modifies an app"
    override val capability = "app.control.read"
    override val requiresNetwork = false
    override suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult {
        val target = args.require("package")
        require(validPackage(target)) { "invalid target package" }
        val plan = CapabilityPlanner.plan(null, false)
        return ToolResult.Success("$target: ${plan.reason}. No modification performed.",
            mapOf("package" to target, "mode" to plan.mode.name, "applied" to "false",
                "runtime" to "NOT_VERIFIED", "embeddedEngine" to "NOT_QUALIFIED"))
    }
}
