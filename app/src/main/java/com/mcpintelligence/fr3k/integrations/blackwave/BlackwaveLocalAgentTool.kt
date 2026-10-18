package com.mcpintelligence.fr3k.integrations.blackwave

import com.mcpintelligence.fr3k.core.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Agent adapter never approves operations or invents device actions. */
class BlackwaveLocalAgentTool(private val client: BlackwaveLocalClient) : AgentTool {
    override val id = "blackwave.local"
    override val displayName = "BLACKWAVE phone fleet"
    override val description = "Authenticated same-phone fleet and local missions. action: health, devices, guides, recipes, recipe.preview(recipeId,requestId), installations, installation(operationId or requestId), capabilities(targetId), preview(requestId,targetId,operation,parameters JSON), operation(operationId or requestId), cancel(operationId). Query capabilities before preview; only published local actions work. Preview requires one exact approval inside BLACKWAVE. No arbitrary hardware actions, shell, approval bypass or internet needed. Reconnect retrieves the original request/job/receipt."
    override val capability = "blackwave"
    override val requiresNetwork = false
    override suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult = withContext(Dispatchers.IO) {
        try {
            val packageName = args.get("package") ?: client.associatedPackages().singleOrNull()
                ?: return@withContext ToolResult.Failure("Select and approve exactly one local BLACKWAVE app in Connections, or supply its approved package.", "blackwave.local.association")
            val action = args.get("action") ?: "health"
            val request = JSONObject().put("version", 1).put("method", action)
            when (action) {
                "health", "devices", "guides", "recipes", "installations" -> Unit
                "recipe.preview" -> request.put("recipe_id", args.require("recipeId")).put("request_id", args.require("requestId"))
                "capabilities" -> request.put("target_id", args.require("targetId"))
                "preview" -> {
                    val target = args.require("targetId")
                    val operation = args.require("operation")
                    val available = client.query(packageName, JSONObject().put("version", 1).put("method", "capabilities").put("target_id", target)).getJSONArray("local_actions")
                    check((0 until available.length()).any { available.getJSONObject(it).getString("id") == operation }) { "Action is not currently published for this target" }
                    val parameters = BlackwaveLocalParameters.parse(args.get("parameters"))
                    request.put("request_id", args.require("requestId")).put("target_id", target).put("action", operation).put("parameters", parameters)
                }
                "operation", "installation" -> if (args.get("operationId") != null) request.put("operation_id", args.require("operationId")) else request.put("request_id", args.require("requestId"))
                "cancel" -> request.put("operation_id", args.require("operationId"))
                else -> return@withContext ToolResult.Failure("Use a published local query or reviewed mission preview; approvals belong to BLACKWAVE.", "blackwave.local.action")
            }
            ToolResult.Success(client.query(packageName, request).toString())
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { ToolResult.Failure(e.message ?: "Local BLACKWAVE request failed", "blackwave.local") }
    }
}
