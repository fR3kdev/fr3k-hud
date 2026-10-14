package com.mcpintelligence.fr3k.core.appcontrol

import java.util.UUID

data class ApprovalBinding(val sessionId: String, val deviceId: String,
    val target: TargetIdentity, val recipeId: String, val recipeSha256: String) {
    init {
        require(sessionId.isNotBlank() && deviceId.isNotBlank() && recipeId.isNotBlank())
        require(validDigest(recipeSha256))
    }
}
/** In-memory approval store: process death revokes every grant. Not exposed as an agent tool. */
class ApprovalGate(private val monotonicMillis: () -> Long) {
    private data class Grant(val binding: ApprovalBinding, val issued: Long, val expires: Long)
    private val grants = mutableMapOf<String, Grant>()
    @Synchronized fun approveFromUser(binding: ApprovalBinding, ttlMillis: Long): String {
        require(ttlMillis in 1..120_000)
        val now = monotonicMillis()
        require(now >= 0 && now <= Long.MAX_VALUE - ttlMillis)
        grants.entries.removeAll { it.value.expires <= now || now < it.value.issued }
        require(grants.size < 128) { "too many pending approvals" }
        val token = UUID.randomUUID().toString()
        grants[token] = Grant(binding, now, now + ttlMillis)
        return token
    }
    @Synchronized fun consume(token: String, binding: ApprovalBinding): Boolean {
        val grant = grants[token] ?: return false
        val now = monotonicMillis()
        if (now < grant.issued || now >= grant.expires) {
            grants.remove(token)
            return false
        }
        if (grant.binding != binding) return false
        grants.remove(token)
        return true
    }
    @Synchronized fun revokeAll() = grants.clear()
}
