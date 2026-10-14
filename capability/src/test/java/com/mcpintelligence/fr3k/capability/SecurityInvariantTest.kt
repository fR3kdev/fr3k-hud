package com.mcpintelligence.fr3k.capability

import org.junit.Assert.*
import org.junit.Test

class SecurityInvariantTest {
    private val sha = "a".repeat(64)
    private val identity = AppIdentity("com.x.y", 1, sha, listOf(sha))

    @Test fun noStepAcceptsFreeFormCode() {
        val banned = listOf("shell", "exec", "script", "eval", "dex", "path", "file", "url")
        Step.ALLOWED_OPS.forEach { op -> assertFalse("$op must not accept code", banned.contains(op)) }
    }

    @Test fun forbiddenCapabilityTokensAreExhaustive() {
        listOf("premium", "drm", "play_integrity", "signature_bypass", "payment", "entitlement").forEach { token ->
            assertTrue(FORBIDDEN_TOKENS.contains(token) || FORBIDDEN_TOKENS.any { it.contains(token) })
        }
    }

    @Test fun passwordFieldsAreNeverEvidence() {
        val tree = UiNode(children = listOf(UiNode(className = "android.widget.EditText", password = true, text = "secret", contentDescription = "pin")))
        assertTrue(SelectorMatcher.find(tree, SelectorFingerprint(textRegex = "secret")) is MatchOutcome.NotFound)
        assertTrue(SelectorMatcher.find(tree, SelectorFingerprint(contentDescRegex = "pin")) is MatchOutcome.NotFound)
    }

    @Test fun identityIsRequiredForEveryDescriptor() {
        assertThrows(IllegalArgumentException::class.java) {
            CapabilityDescriptor("x.y", Plane.BRIDGE, RiskClass.OBSERVE, "s", identity, emptyList())
        }
    }
}
