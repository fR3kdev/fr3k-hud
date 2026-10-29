package com.mcpintelligence.fr3k.integrations.opencodego

import com.mcpintelligence.fr3k.core.Fr3kPlugin
import com.mcpintelligence.fr3k.integrations.hermes.AiProviderRegistry
import com.mcpintelligence.fr3k.protocol.Capabilities
import com.mcpintelligence.fr3k.protocol.Capability
import com.mcpintelligence.fr3k.protocol.CapabilityTier

/**
 * OpenCode Go integration plugin. Registers [OpenCodeGoProvider] (subscription
 * API key, curated coding models) and [AskOpenCodeGoCommand], so OpenCode Go
 * appears alongside OpenRouter / OpenCode Zen in the provider registry.
 */
class OpenCodeGoPlugin(
    private val provider: OpenCodeGoProvider,
    private val aiProviderRegistry: AiProviderRegistry,
    private val commandFactory: () -> AskOpenCodeGoCommand,
) : Fr3kPlugin {

    override val pluginId: String = "fr3k.integrations.opencodego"
    override val displayName: String = "OpenCode Go"
    override val version: String = "0.1.0"

    override fun capabilities() = listOf(
        Capability(
            id = Capabilities.AI_LOCAL_CHAT,
            displayName = "OpenCode Go chat",
            description = "Cloud chat via the OpenCode Go subscription (API key in secure store)",
            tier = CapabilityTier.TIER_0,
        ),
    )

    override fun commands() = listOf(commandFactory())

    override suspend fun start() {
        aiProviderRegistry.register(provider)
        // Warm the catalogue only when a key is present; failures are non-fatal.
        runCatching { provider.refreshModels() }
    }

    override suspend fun stop() {
        aiProviderRegistry.unregister(provider.id)
    }
}
