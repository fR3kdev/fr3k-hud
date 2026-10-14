package com.mcpintelligence.fr3k.core.appcontrol

import com.mcpintelligence.fr3k.core.tools.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AppControlCoreTest {
    private val hash = "a".repeat(64)
    private fun target() = TargetIdentity("com.mcpintelligence.fr3k.patchfixture", 1, hash, listOf(hash))
    @Test fun identityDefensivelyCopiesSplits() {
        val splits = mutableListOf(hash)
        val identity = TargetIdentity("com.example.fixture", 1, hash, splits)
        splits.clear()
        assertEquals(listOf(hash), identity.splitSha256)
        assertThrows(UnsupportedOperationException::class.java) {
            (identity.splitSha256 as MutableList).clear()
        }
    }
    @Test fun invalidIdentityRejected() {
        assertThrows(IllegalArgumentException::class.java) { TargetIdentity("x;id", 1, hash, listOf(hash)) }
        assertThrows(IllegalArgumentException::class.java) { TargetIdentity("com.x.app", 1, "invalid", listOf(hash)) }
        assertThrows(IllegalArgumentException::class.java) { TargetIdentity("com.x.app", 1, hash, emptyList()) }
    }
    @Test fun exactRegistryMatchOnly() {
        val t = target()
        val registry = RecipeRegistry(listOf(Recipe("fixture.banner", hash, t, true)))
        assertNotNull(registry.find("fixture.banner", t))
        val other = TargetIdentity(t.packageName, 2, hash, listOf(hash))
        assertNull(registry.find("fixture.banner", other))
    }
    @Test fun noUniversalRecipeFallback() {
        assertEquals(Mode.UNSUPPORTED, CapabilityPlanner.plan(null, false).mode)
    }
    @Test fun runtimeRequiredForLive() {
        val recipe = Recipe("fixture.banner", hash, target(), true)
        assertEquals(Mode.PREPARE_AND_RESTART, CapabilityPlanner.plan(recipe, false).mode)
        assertEquals(Mode.LIVE, CapabilityPlanner.plan(recipe, true).mode)
    }
    @Test fun staticRecipeNeverClaimsLive() {
        val recipe = Recipe("fixture.banner", hash, target(), false)
        assertEquals(Mode.PREPARE_AND_RESTART, CapabilityPlanner.plan(recipe, true).mode)
    }
    @Test fun approvalIsBoundAndSingleUse() {
        var now = 10L
        val gate = ApprovalGate { now }
        val binding = ApprovalBinding("session", "device", target(), "fixture.banner", hash)
        val token = gate.approveFromUser(binding, 100)
        assertFalse(gate.consume(token, binding.copy(sessionId = "other")))
        assertTrue(gate.consume(token, binding))
        assertFalse(gate.consume(token, binding))
    }
    @Test fun expiredApprovalRejected() {
        var now = 10L
        val gate = ApprovalGate { now }
        val binding = ApprovalBinding("s", "d", target(), "fixture.banner", hash)
        val token = gate.approveFromUser(binding, 100)
        now = 110
        assertFalse(gate.consume(token, binding))
    }
    @Test fun changedRecipeOrDeviceRejected() {
        val gate = ApprovalGate { 10L }
        val binding = ApprovalBinding("s", "d", target(), "fixture.banner", hash)
        val token = gate.approveFromUser(binding, 100)
        assertFalse(gate.consume(token, binding.copy(recipeSha256 = "b".repeat(64))))
        assertFalse(gate.consume(token, binding.copy(deviceId = "other")))
        assertTrue(gate.consume(token, binding))
    }
    @Test fun approvalRevocationAndClockRegressionFailClosed() {
        var now = 10L
        val gate = ApprovalGate { now }
        val binding = ApprovalBinding("s", "d", target(), "fixture.banner", hash)
        val token = gate.approveFromUser(binding, 100)
        gate.revokeAll()
        assertFalse(gate.consume(token, binding))
        val second = gate.approveFromUser(binding, 100)
        now = 9
        assertFalse(gate.consume(second, binding))
    }
    @Test fun unknownAppPlanDoesNotApplyAnything() = runTest {
        val bus = AgentToolBus().also { it.register(AppControlPlanTool()) }
        val result = bus.dispatch(ToolContext("s", deviceId = "d"), "app.control.plan",
            ToolArgs(mapOf("package" to "com.google.android.youtube", "runtimeLoaded" to "true")))
        assertTrue(result is ToolResult.Success)
        assertEquals("UNSUPPORTED", (result as ToolResult.Success).structured["mode"])
        assertEquals("false", result.structured["applied"])
    }
    @Test fun missingTargetIsError() = runTest {
        val bus = AgentToolBus().also { it.register(AppControlPlanTool()) }
        assertTrue(bus.dispatch(ToolContext("s", deviceId = "d"), "app.control.plan",
            ToolArgs(emptyMap())) is ToolResult.Failure)
    }
}
