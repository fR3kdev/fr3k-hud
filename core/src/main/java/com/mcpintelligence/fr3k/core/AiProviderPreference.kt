package com.mcpintelligence.fr3k.core

/**
 * Which AI provider the whole application uses for its own chat surfaces: the chat bubble, the mini
 * browser, the shared agent session and the model picker.
 *
 * The operator's rule is that a configured OpenCode key replaces OpenRouter everywhere. OpenCode wins
 * whenever its key is present, even when an OpenRouter key is also present. An explicit choice in
 * Settings always beats the automatic rules. With no keys configured at all, the zero-key OpenCode Go
 * path is the only one that can actually answer, so it is the default rather than a provider that
 * silently fails every request.
 */
object AiProviderPreference {
    const val AUTO = "auto"
    const val OPENCODE = "opencode"
    const val OPENROUTER = "openrouter"

    fun resolve(
        explicit: String?,
        openCodeKeyConfigured: Boolean,
        openRouterKeyConfigured: Boolean,
        openCodeReachable: Boolean = true,
    ): String = when (explicit?.trim()?.lowercase()) {
        OPENCODE -> OPENCODE
        OPENROUTER -> OPENROUTER
        else -> when {
            openCodeKeyConfigured -> OPENCODE
            openRouterKeyConfigured && openCodeReachable -> OPENROUTER
            openRouterKeyConfigured -> OPENROUTER
            else -> if (openCodeReachable) OPENCODE else OPENROUTER
        }
    }
}
