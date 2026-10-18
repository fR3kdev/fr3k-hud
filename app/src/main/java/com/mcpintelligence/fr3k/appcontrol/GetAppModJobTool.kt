package com.mcpintelligence.fr3k.appcontrol

import com.mcpintelligence.fr3k.capability.appmod.ModificationStage
import com.mcpintelligence.fr3k.core.tools.AgentTool
import com.mcpintelligence.fr3k.core.tools.ToolArgs
import com.mcpintelligence.fr3k.core.tools.ToolContext
import com.mcpintelligence.fr3k.core.tools.ToolResult

/** Read-only durable job query. Exposes stage/verification/installer status, never a filesystem path. */
class GetAppModJobTool(private val store: ModificationStore) : AgentTool {
    override val id = "app.control.job"
    override val displayName = "Read app-mod job"
    override val description =
        "Returns the durable stage, verification and installer status of one app-modification job by id."
    override val capability = "app.control.job"
    override val requiresNetwork = false

    override suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult {
        val jobId = args.require("job_id")
        val job = store.getJob(jobId)
            ?: return ToolResult.Failure("no job with id $jobId", "job.notfound")
        return ToolResult.Success(
            "job=$jobId stage=${job.stage.name} verification=${job.verification.name} installer=${job.installerStatus.name}",
            mapOf(
                "jobId" to job.jobId,
                "stage" to job.stage.name,
                "verification" to job.verification.name,
                "installerStatus" to job.installerStatus.name,
                "package" to (job.artifact?.packageName ?: ""),
                "outputSha256" to (job.outputSha256 ?: ""),
                "error" to (job.error ?: ""),
            ),
        )
    }
}
