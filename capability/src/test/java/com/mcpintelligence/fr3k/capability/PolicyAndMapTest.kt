package com.mcpintelligence.fr3k.capability

import org.junit.Assert.*
import org.junit.Test

class PolicyAndMapTest {
    private val sha = "a".repeat(64)
    private val identity = AppIdentity("com.mcpintelligence.fr3k.patchfixture", 1, sha, listOf(sha))

    @Test fun onlyAllowListedOpsAndModesAreAccepted() {
        assertThrows(IllegalArgumentException::class.java) { PolicyRule("com.x.y", "SHELL_EXEC", "allow") }
        assertThrows(IllegalArgumentException::class.java) { PolicyRule("com.x.y", "WAKE_LOCK", "sudo") }
        assertThrows(IllegalArgumentException::class.java) { PrivilegedOps.requireAllowed("FORCE_STOP_PACKAGE") }
        PrivilegedOps.requireAllowed("WAKE_LOCK")
        PrivilegedOps.requireMode("ignore")
    }

    @Test fun plannerProducesMinimalDiffWithInverses() {
        val document = PolicyDocument(1, listOf(PolicyRule("com.mcpintelligence.*", "WAKE_LOCK", "deny")))
        val observed = mapOf(("com.mcpintelligence.fr3k.patchfixture" to "WAKE_LOCK") to "allow")
        val changes = PolicyPlanner.diff(document, observed, listOf("com.mcpintelligence.fr3k.patchfixture", "com.other.app"))
        assertEquals(1, changes.size)
        assertEquals("deny", changes[0].to)
        assertEquals("allow", changes[0].inverse.to)
        val noop = PolicyPlanner.diff(document, mapOf(("com.mcpintelligence.fr3k.patchfixture" to "WAKE_LOCK") to "deny"), listOf("com.mcpintelligence.fr3k.patchfixture"))
        assertTrue(noop.isEmpty())
    }

    @Test fun mapCompilerEmitsOneCommandPerScreenAndPropagatesRisk() {
        val map = AppMap(
            identity = identity,
            screens = listOf(
                MapElement2("home_screen", listOf(Step.Launch("fr3kfixture://home")), listOf(MapElement("banner", SelectorFingerprint(viewId = "fixture-owned-banner")))),
                MapElement2("ad_demo", listOf(Step.Launch("fr3kfixture://ads")), emptyList()),
                MapElement2("policy_screen", listOf(Step.PrivilegedOp("com.x.y", "WAKE_LOCK", "deny")), emptyList()),
            ),
            edges = listOf(MapEdge("home_screen", Step.Click(SelectorFingerprint(viewId = "fixture-owned-banner")), "ad_demo")),
        )
        val commands = MapCommandCompiler.compile(map)
        assertEquals(3, commands.size)
        assertTrue(commands.map { it.id }.contains("map.patchfixture.home.screen"))
        assertEquals(RiskClass.PRIVILEGED, commands.single { it.id.endsWith("policy.screen") }.risk)
        assertEquals(Plane.PRIVILEGED, commands.single { it.id.endsWith("policy.screen") }.plane)
        commands.forEach { assertTrue(it.summary.startsWith("Open ")) }
    }

    @Test fun mapRoundTripsAndDetectsStaleness() {
        val map = AppMap(identity, listOf(MapElement2("home", listOf(Step.Launch("fr3kfixture://home")), emptyList())), emptyList())
        val restored = AppMap.fromJson(map.toJson())
        assertEquals(map.identity, restored.identity)
        assertEquals(1, restored.screens.size)
        assertFalse(restored.stale(identity))
        assertTrue(restored.stale(identity.copy(versionCode = 5)))
    }
}
