package com.mcpintelligence.fr3k.capability

import org.junit.Assert.*
import org.junit.Test

class PlanAndWorkflowTest {
    private val sha = "a".repeat(64)
    private val pkg = "com.mcpintelligence.fr3k.patchfixture"
    private val identity = AppIdentity(pkg, 1, sha, listOf(sha))
    private val descriptor = CapabilityDescriptor(
        id = "fixture.banner.toggle",
        plane = Plane.BRIDGE,
        risk = RiskClass.REVERSIBLE_WRITE,
        summary = "Toggle the owned fixture banner",
        identity = identity,
        steps = listOf(Step.Broadcast("com.mcpintelligence.fr3k.hud.SET_OWNED_FIXTURE_BANNER", mapOf("visible" to "\$visible"))),
        parameters = mapOf("visible" to ParamType.BOOL),
    )
    private val registry = RegistryStore().apply {
        put(RegistryEntry(descriptor, "fr3k.fixture", sha, SignerVerdict(true, "test")))
    }
    private val installed = mapOf(pkg to identity)

    @Test fun planRequiresTheExactInstalledBuild() {
        val step = PlanStep("fixture.banner.toggle", identity, mapOf("visible" to "false"))
        assertEquals(1, PlanGraph.build("hide banner", listOf(step), registry, installed).steps.size)
        val drifted = identity.copy(versionCode = 2)
        val error = assertThrows(PlanRefusedException::class.java) {
            PlanGraph.build("hide banner", listOf(PlanStep("fixture.banner.toggle", drifted, mapOf("visible" to "false"))), registry, installed)
        }
        assertTrue(error.message!!.contains("expected") && error.message!!.contains("observed"))
    }

    @Test fun planRefusesUnknownCapabilityAndMissingApp() {
        val error = assertThrows(PlanRefusedException::class.java) {
            PlanGraph.build("do a thing", listOf(PlanStep("no.such.capability", identity)), registry, installed)
        }
        assertTrue(error.message!!.contains("not registered"))
        val missing = assertThrows(PlanRefusedException::class.java) {
            PlanGraph.build("do a thing", listOf(PlanStep("fixture.banner.toggle", identity)), registry, emptyMap())
        }
        assertTrue(missing.message!!.contains("not installed"))
    }

    @Test fun rendererDescribesAppRiskAndUndo() {
        val plan = PlanGraph.build("hide banner", listOf(PlanStep("fixture.banner.toggle", identity, mapOf("visible" to "false"))), registry, installed)
        val text = PlanRenderer.render(plan, registry)
        assertTrue(text.contains("GOAL: hide banner"))
        assertTrue(text.contains(pkg))
        assertTrue(text.contains("REVERSIBLE_WRITE"))
        assertTrue(text.contains("undo:") && text.contains("reverse"))
        assertTrue(text.contains("parameters: visible=false"))
    }

    @Test fun workflowBindsAcrossTwoAppsAndReportsDrift() {
        val second = AppIdentity("com.mcpintelligence.fr3k.patchfixturetwo", 1, sha, listOf(sha))
        val secondDescriptor = descriptor.copy(
            id = "target.two.ack",
            identity = second,
            summary = "Acknowledge the owned second target",
            steps = listOf(Step.Launch("fr3ktwo://open"), Step.Observe(300)),
            parameters = emptyMap(),
        )
        val two = RegistryStore().apply {
            put(RegistryEntry(descriptor, "fr3k.fixture", sha, SignerVerdict(true, "test")))
            put(RegistryEntry(secondDescriptor, "fr3k.two", sha, SignerVerdict(true, "test")))
        }
        val spec = WorkflowSpec(
            id = "fr3k.demo.duo",
            name = "Owned duo demo",
            version = 1,
            steps = listOf(
                WorkflowStep("fixture.banner.toggle", identity, mapOf("visible" to "false")),
                WorkflowStep("target.two.ack", second),
            ),
        )
        val bound = WorkflowBinder.bind(spec, two, installed + (second.packageName to second))
        assertEquals(2, bound.size)
        assertEquals(2, bound.map { it.descriptor.identity.packageName }.distinct().size)
        val drifted = installed + (second.packageName to second.copy(versionCode = 9))
        val error = assertThrows(WorkflowBindException::class.java) { WorkflowBinder.bind(spec, two, drifted) }
        assertTrue(error.message!!.contains("expected") && error.message!!.contains("observed"))
    }
}
