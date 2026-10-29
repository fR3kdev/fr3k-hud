package com.mcpintelligence.fr3k.integrations.providers

import java.util.UUID

/**
 * OpenCode Go/Zen rejects requests without a stable `x-opencode-session` header
 * (`MissingSessionID`), which is what "request is missing x-opencode-session" means in the UI.
 *
 * The id is opaque and only used for backend affinity and prompt-cache reuse, so it must stay the
 * same for the life of a conversation — and ideally across restarts. [installPersisted] lets the app
 * supply one from encrypted-free prefs; without it a per-process id is used and everything still works.
 */
object OpenCodeSession {
    private val generated = "ses_" + UUID.randomUUID().toString().replace("-", "").take(24)

    @Volatile
    private var persisted: String? = null

    fun installPersisted(value: String?) {
        if (!value.isNullOrBlank() && value.startsWith("ses_")) persisted = value
    }

    val id: String get() = persisted ?: generated

    /** Shape check so a corrupt preference cannot produce an invalid header. */
    fun valid(value: String?): Boolean =
        !value.isNullOrBlank() && value.startsWith("ses_") && value.length in 8..128 &&
            value.all { it.isLetterOrDigit() || it == '_' || it == '-' }
}
