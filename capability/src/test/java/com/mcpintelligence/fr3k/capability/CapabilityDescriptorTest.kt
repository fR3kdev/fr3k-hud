package com.mcpintelligence.fr3k.capability

import org.junit.Assert.*
import org.junit.Test

class CapabilityDescriptorTest {
    private val sha = "a".repeat(64)
    private val identity = AppIdentity("com.x.y", 1, sha, listOf(sha))
    private val steps = listOf(Step.Observe(100))

    private fun descriptor(
        id: String = "demo.toggle",
        summary: String = "Toggle a demo flag",
        parameters: Map<String, ParamType> = emptyMap(),
        stepList: List<Step> = steps,
    ) = CapabilityDescriptor(id, Plane.BRIDGE, RiskClass.REVERSIBLE_WRITE, summary, identity, stepList, parameters)

    @Test fun doctrineRefusalsCannotBeConstructed() {
        listOf("premium.unlock", "drm.bypass", "play.integrity.spoof", "signature.bypass").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { descriptor(id = id) }
        }
        assertThrows(IllegalArgumentException::class.java) { descriptor(summary = "Unlock premium features") }
    }

    @Test fun parametersAreSubstitutedIntoSteps() {
        val d = descriptor(parameters = mapOf("visible" to ParamType.BOOL), stepList = listOf(Step.Broadcast("a.b", mapOf("visible" to "\$visible"))))
        val (step) = d.instantiate(mapOf("visible" to "false"))
        assertEquals("false", (step as Step.Broadcast).extras["visible"])
    }

    @Test fun missingAndUnknownParametersAreRefused() {
        val d = descriptor(parameters = mapOf("visible" to ParamType.BOOL), stepList = listOf(Step.Broadcast("a.b", mapOf("visible" to "\$visible"))))
        assertThrows(IllegalArgumentException::class.java) { d.instantiate(emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { d.instantiate(mapOf("visible" to "true", "extra" to "1")) }
        assertThrows(IllegalArgumentException::class.java) { d.instantiate(mapOf("visible" to "yes")) }
    }

    @Test fun undeclaredTokenIsRefused() {
        val d = descriptor(stepList = listOf(Step.Broadcast("a.b", mapOf("x" to "\$nope"))))
        assertThrows(IllegalArgumentException::class.java) { d.instantiate(emptyMap()) }
    }
}
