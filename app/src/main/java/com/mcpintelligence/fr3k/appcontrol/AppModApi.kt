package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import android.content.Intent
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.*

class AppModApi(private val context: Context) {
    private val app get() = context.applicationContext as Fr3kApplication
    suspend fun call(name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        require(args.toString().toByteArray().size <= 65536)
        val allowed = when (name) {
            "analyze_apk" -> setOf("package", "artifact_id")
            "suggest_app_mods" -> setOf("analysis_id", "goal", "research_mode")
            "preview_app_mods" -> setOf("analysis_id", "selections", "installation_mode")
            "request_app_mod_preparation" -> setOf("preview_id")
            "get_app_mod_job" -> setOf("job_id")
            else -> error("Unknown app-modification tool")
        }
        require(args.keys().asSequence().all { it in allowed }) { "Unknown argument" }
        val workflow = app.appModWorkflow
        when (name) {
            "analyze_apk" -> {
                require(args.has("package") xor args.has("artifact_id")) { "Choose package or previously imported artifact_id" }
                val source = if (args.has("package")) workflow.artifacts.installed(args.getString("package")) else workflow.artifacts.get(args.getString("artifact_id"))
                val report = workflow.analyze(source.id)
                JSONObject().put("analysisId", report.analysisId).put("jobId", report.analysisId).put("artifactId", source.id)
                    .put("package", report.artifact.packageName).put("fingerprint", report.artifact.fingerprint).put("completed", report.completed)
                    .put("evidenceCount", report.evidence.size).put("limits", JSONArray(report.limits))
            }
            "suggest_app_mods" -> {
                val id = args.getString("analysis_id"); val report = requireNotNull(workflow.store.getAnalysis(id))
                val mode = args.optString("research_mode", "cache"); require(mode in setOf("cache", "refresh"))
                if (mode == "refresh") {
                    check(app.settings.settings.value.consentProfile != com.mcpintelligence.fr3k.core.ConsentLevel.LOCAL_ONLY)
                    AppModResearch.refresh(workflow.store, report)
                }
                JSONObject().put("analysisId", id).put("suggestions", JSONArray().also { out -> workflow.suggestions(id, args.optString("goal")).forEach { out.put(it.toJson()) } })
            }
            "preview_app_mods" -> workflow.preview(args.getString("analysis_id"),
                if (args.get("selections") is JSONArray) args.getJSONArray("selections") else JSONArray(args.getString("selections")), args.optString("installation_mode", "auto"))
            "request_app_mod_preparation" -> {
                val id = args.getString("preview_id"); requireNotNull(workflow.store.record("preview", id))
                withContext(Dispatchers.Main) { context.startActivity(Intent(context, com.mcpintelligence.fr3k.ui.appcontrol.AppModDiscoveryActivity::class.java)
                    .putExtra("preview_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                JSONObject().put("previewId", id).put("status", "AWAITING_USER_REVIEW").put("prepared", false)
            }
            else -> {
                val job = requireNotNull(workflow.store.getJob(args.getString("job_id")))
                JSONObject().put("jobId", job.jobId).put("stage", job.stage.name).put("verification", job.verification.name)
                    .put("installerStatus", job.installerStatus.name).put("outputSha256", job.outputSha256 ?: JSONObject.NULL).put("error", job.error ?: JSONObject.NULL)
            }
        }
    }
}

class AppModContractTool(private val context: Context, private val operation: String) : AgentTool {
    override val id = "app.mods.$operation"
    override val displayName = operation.replace('_', ' ')
    override val description = "Phone-only $operation. Preparation opens exact user review; no agent approval argument is accepted."
    override val capability = "app.mods.$operation"
    override val requiresNetwork = false
    override suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult = try {
        val result = AppModApi(this.context).call(operation, JSONObject(args.values as Map<*, *>))
        ToolResult.Success(result.toString(), result.keys().asSequence().associateWith { result.get(it).toString() })
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (failure: Exception) { ToolResult.Failure(failure.message ?: "App modification request failed", "appmod.refused") }
}
