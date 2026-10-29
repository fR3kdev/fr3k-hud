package com.mcpintelligence.fr3k.integrations.opencodego

import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.Fr3kCommand
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.protocol.AgentAskRequest
import com.mcpintelligence.fr3k.protocol.AgentProfile
import com.mcpintelligence.fr3k.protocol.Capabilities

/**
 * "Ask OpenCode Go" command. Mirrors [com.mcpintelligence.fr3k.integrations.openrouter.AskOpenRouterCommand]
 * but routes to [OpenCodeGoProvider] with the user's subscription API key.
 */
class AskOpenCodeGoCommand(
    private val provider: () -> OpenCodeGoProvider,
) : Fr3kCommand {

    override val id: String = "agent.ask.opencodego"
    override val title: String = "Ask OpenCode Go"
    override val description: String = "Send the prompt to an OpenCode Go model using the stored API key"
    override val requiredCapabilities: Set<String> = setOf(Capabilities.AGENT_ASK)
    override val keywords: Set<String> = setOf("ask", "ai", "opencode", "go", "code", "explain")
    override val pluginId: String = "fr3k.integrations.opencodego"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        val prompt = args["prompt"]
            ?: context.selectedText
            ?: context.fullText
            ?: context.currentUrl
            ?: "Describe the current context."
        val model = args["model"]?.takeIf { it.isNotBlank() }
        val request = AgentAskRequest(
            prompt = prompt,
            model = model,
            context = context.asAgentContext(),
            profile = AgentProfile.NORMAL,
        )
        val response = provider().ask(request)
        return CommandResult.Ok(
            message = response.text,
            data = mapOf(
                "format" to response.format.name,
                "model" to (response.model ?: model ?: OpenCodeGoProvider.DEFAULT_MODEL),
            ),
        )
    }
}
