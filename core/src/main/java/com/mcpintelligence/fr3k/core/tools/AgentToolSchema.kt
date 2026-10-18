package com.mcpintelligence.fr3k.core.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * The OpenAI-compatible function schema for the shared agent tools, built once and used by every
 * provider. Two providers with hand-copied schemas drift, and a drifted schema is a tool the model
 * cannot call correctly — so the parameter contract lives here and nowhere else.
 *
 * The schema is closed: a tool declares exactly the fields it accepts, nothing is additional, and the
 * required set is per capability rather than "everything the model feels like sending".
 */
object AgentToolSchema {

    fun parameterNames(capability: String): List<String> = when (capability) {
        "app.mods.analyze_apk" -> listOf("package", "artifact_id")
        "app.mods.suggest_app_mods" -> listOf("analysis_id", "goal", "research_mode")
        "app.mods.preview_app_mods" -> listOf("analysis_id", "selections", "installation_mode")
        "app.mods.request_app_mod_preparation" -> listOf("preview_id")
        "app.mods.get_app_mod_job" -> listOf("job_id")
        "app.control.read" -> listOf("package")
        "app.control.suggest" -> listOf("analysis_id", "goal")
        "app.control.job" -> listOf("job_id")
        "app.control.fixture.write" -> listOf("visible")
        "browser" -> listOf("action", "url", "selector", "text", "x", "y")
        "termux" -> listOf("command", "cwd")
        else -> listOf("action", "deviceId", "url")
    }

    fun requiredNames(capability: String): List<String> = when (capability) {
        "app.mods.analyze_apk" -> emptyList()
        "app.mods.suggest_app_mods" -> listOf("analysis_id")
        "app.mods.preview_app_mods" -> listOf("analysis_id", "selections")
        "app.mods.request_app_mod_preparation" -> listOf("preview_id")
        "app.mods.get_app_mod_job" -> listOf("job_id")
        "app.control.read" -> listOf("package")
        "app.control.suggest" -> listOf("analysis_id")
        "app.control.job" -> listOf("job_id")
        "app.control.fixture.write" -> listOf("visible")
        else -> emptyList()
    }

    fun openAiFunctions(tools: List<AgentTool>): JSONArray = JSONArray().also { functions ->
        tools.forEach { tool ->
            val properties = JSONObject()
            parameterNames(tool.capability).forEach { properties.put(it, JSONObject().put("type", "string")) }
            functions.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject()
                        .put("name", SharedAgentSession.functionName(tool.id))
                        .put("description", tool.description)
                        .put("parameters", JSONObject().put("type", "object")
                            .put("properties", properties)
                            .put("required", JSONArray(requiredNames(tool.capability)))
                            .put("additionalProperties", false)),
                ),
            )
        }
    }
}
