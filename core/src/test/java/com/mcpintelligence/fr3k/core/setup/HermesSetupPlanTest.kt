package com.mcpintelligence.fr3k.core.setup

import org.junit.Assert.*
import org.junit.Test

class HermesSetupPlanTest {
    @Test fun `readiness requires exact fresh successful live response`() {
        assertFalse(HermesSetupPlan.isVerified(0, "", "", "abc123"))
        assertFalse(HermesSetupPlan.isVerified(0, "FR3K_HERMES_READY_other", "", "abc123"))
        assertFalse(HermesSetupPlan.isVerified(124, "FR3K_HERMES_READY_abc123", "", "abc123"))
        assertFalse(HermesSetupPlan.isVerified(0, "FR3K_HERMES_READY_abc123", "warning", "abc123"))
        assertTrue(HermesSetupPlan.isVerified(0, "FR3K_HERMES_READY_abc123\n", "", "abc123"))
        assertThrows(IllegalArgumentException::class.java) { HermesSetupPlan.verification("groq", "model", "abc123", false) }
        assertThrows(IllegalArgumentException::class.java) { HermesSetupPlan.verification("groq", "model", "$(bad)", true) }
        val script = HermesSetupPlan.verification("groq", "llama-3.3-70b-versatile", "abc123", true)
        assertTrue(script.contains("--oneshot"))
        assertTrue(script.contains("--provider 'custom'"))
        assertTrue(script.contains("--base-url 'https://api.groq.com/openai/v1'"))
        assertTrue(script.contains("--model 'llama-3.3-70b-versatile'"))
        assertTrue(script.contains("test \"$" + "reply\" = 'FR3K_HERMES_READY_abc123'"))
    }
    @Test fun `configuration accepts metadata only and isolates phone profile`() {
        assertThrows(IllegalArgumentException::class.java) { HermesSetupPlan.configure("openrouter", "model", false) }
        assertThrows(IllegalArgumentException::class.java) { HermesSetupPlan.configure("bad; touch x", "model", true) }
        assertThrows(IllegalArgumentException::class.java) { HermesSetupPlan.configure("openrouter", "$(touch x)", true) }
        val script = HermesSetupPlan.configure("openrouter", "openai/gpt-4.1", true)
        assertTrue(script.contains("model.provider 'openrouter'"))
        assertTrue(script.contains("model.default 'openai/gpt-4.1'"))
        assertTrue(script.contains("approvals.mode manual"))
        assertTrue(script.contains("security.redact_secrets true"))
        assertTrue(script.contains("SOUL.md"))
        assertTrue(script.contains("hermes config get model.provider"))
        assertTrue(script.contains("hermes config get model.default"))
        assertTrue(script.contains("hermes config get approvals.mode"))
        assertTrue(script.contains("hermes config get security.redact_secrets"))
        assertTrue(script.contains("profiles/fr3k-hud"))
        assertFalse(script.contains("API_KEY"))
    }
    @Test fun `installation requires explicit consent and supported architecture`() {
        assertThrows(IllegalArgumentException::class.java) { HermesSetupPlan.install(false, true) }
        assertThrows(IllegalArgumentException::class.java) { HermesSetupPlan.install(true, false) }
        val script = HermesSetupPlan.install(true, true)
        assertTrue(script.contains("signed-by="))
        assertTrue(script.contains("C572B5FDD1A29CCFA9A912B6840B0848E139156D"))
        assertTrue(script.contains("pkg install -y hermes-agent"))
        assertFalse(script.contains("install.sh"))
        assertFalse(script.contains("trusted=yes"))
    }
}
