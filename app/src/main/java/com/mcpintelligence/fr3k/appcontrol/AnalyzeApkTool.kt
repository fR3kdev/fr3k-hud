package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import com.mcpintelligence.fr3k.appcontrol.analysis.ApkAnalyzer
import com.mcpintelligence.fr3k.core.tools.AgentTool
import com.mcpintelligence.fr3k.core.tools.ToolArgs
import com.mcpintelligence.fr3k.core.tools.ToolContext
import com.mcpintelligence.fr3k.core.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * `analyze_apk` for the installed-app path: package -> ArtifactIdentity -> ApkAnalyzer ->
 * AnalysisReport persisted to the ModificationStore. Read-only with respect to the target app:
 * the census runs on the installed base APK and never writes into the target's private data.
 *
 * The returned `analysisId` is the durable artifact id that `suggest_app_mods` and the review
 * screens will consume; no filesystem path is ever exposed to the agent.
 */
class AnalyzeApkTool(
    private val context: Context,
    private val store: ModificationStore,
) : AgentTool {
    override val id = "app.control.analyze"
    override val displayName = "Analyze installed app"
    override val description =
        "Indexes an installed app's base APK (DEX census + technology indicators), persists the " +
            "feature map, and returns an analysisId for later suggestion queries. Never modifies the app."
    override val capability = "app.control.read"
    override val requiresNetwork = false

    override suspend fun invoke(toolContext: ToolContext, args: ToolArgs): ToolResult {
        val pkg = args.require("package")
        require(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "invalid package name" }
        return try {
            val report = withContext(Dispatchers.IO) {
                val app = context.applicationContext as com.mcpintelligence.fr3k.Fr3kApplication
                val source = app.appModWorkflow.artifacts.installed(pkg)
                app.appModWorkflow.analyze(source.id)
            }
            ToolResult.Success(
                "Analyzed $pkg -> analysisId=${report.analysisId}. " +
                    (report.evidence.firstOrNull { it.evidenceId == "dex-census" }?.feature ?: "no DEX census."),
                mapOf(
                    "analysisId" to report.analysisId,
                    "package" to pkg,
                    "fingerprint" to report.artifact.fingerprint,
                    "technology" to report.technology.joinToString(","),
                    "completed" to report.completed.toString(),
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ToolResult.Failure(e.message ?: e.javaClass.simpleName, "analysis.failed")
        }
    }
}
