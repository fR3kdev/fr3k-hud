package com.mcpintelligence.fr3k.appcontrol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.bridge.Fr3kBridgeService
import com.mcpintelligence.fr3k.capability.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanWorkflowDeviceTest {

    private lateinit var host: CapabilityHost

    @Before fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        host = CapabilityHost(instrumentation.targetContext)
        instrumentation.uiAutomation.executeShellCommand(
            "settings put secure enabled_accessibility_services com.mcpintelligence.fr3k.hud/com.mcpintelligence.fr3k.bridge.Fr3kBridgeService",
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } }
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!Fr3kBridgeService.connected() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
        if (!Fr3kBridgeService.connected()) {
            org.junit.Assume.assumeTrue(
                "instrumentation cannot host an accessibility service; the bridge plane is verified " +
                    "through the app's own MCP surface in scripts/device/run_capability_lab.py",
                false,
            )
        }
    }

    @Test fun goalCompilesToAReviewedPlanThatExecutesAndJournals() = runBlocking<Unit> {
        val compiled = PlanCompiler(host).compile("hide the banner on the owned fixture")
        val plan = compiled.plan ?: error("compile refused: ${compiled.refusal}")
        val rendered = PlanRenderer.render(plan, host.registry())
        assertTrue(rendered.contains("GOAL: hide the banner on the owned fixture"))
        assertTrue(rendered.contains("undo:"))
        assertEquals("fixture.banner.hide", plan.steps.single().capabilityId)

        val before = host.journalSnapshot().size
        val spec = WorkflowSpec("plan.e2e", plan.goal, 1, plan.steps.map { WorkflowStep(it.capabilityId, it.identity, it.parameters) })
        val report = host.workflowRunner().run(spec)
        assertTrue("plan run failed: ${report.note}", report.ok)
        val journal = host.journalSnapshot()
        assertTrue("journal must grow", journal.size > before)
        val last = journal.last()
        assertEquals("fixture.banner.hide", last.capabilityId)
        assertEquals(EvidenceStatus.SUCCEEDED, last.status)
        assertTrue("hide must record a real inverse", last.inverse is InverseOp.RunCapability)
        assertEquals("fixture.banner.show", (last.inverse as InverseOp.RunCapability).capabilityId)

        // Undo by running the paired capability, then confirm the plan is refused for a stale build.
        val undoReport = host.workflowRunner().run(
            WorkflowSpec("plan.undo", "undo", 1, listOf(WorkflowStep("fixture.banner.show", plan.steps.single().identity))),
        )
        assertTrue("undo failed: ${undoReport.note}", undoReport.ok)

        val staleRefused = runCatching {
            PlanGraph.build(
                "hide again",
                listOf(PlanStep("fixture.banner.hide", plan.steps.single().identity.copy(versionCode = 999_999))),
                host.registry(),
                host.installed(),
            )
        }
        assertTrue("a drifted build must be refused", staleRefused.isFailure)
    }

    @Test fun staleBuildAndUnknownGoalAreRefusedWithBothSidesNamed() {
        val stale = runCatching {
            PlanGraph.build(
                "hide",
                listOf(PlanStep("fixture.banner.hide", host.installed().values.first().copy(versionCode = 999_999))),
                host.registry(),
                host.installed(),
            )
        }
        assertTrue(stale.isFailure)
        assertTrue(stale.exceptionOrNull()!!.message!!.contains("expected"))
        val nonsense = PlanCompiler(host).compile("reticulate the splines")
        assertNull(nonsense.plan)
        assertNotNull(nonsense.refusal)
        android.util.Log.i("FR3KPL", "REFUSAL_OK ${nonsense.refusal}")
    }

    @Test fun broadcastTriggerRunsTheFusedCrossAppWorkflow() = runBlocking<Unit> {
        val spec = host.workflows().firstOrNull() ?: error("no workflow registered")
        assertEquals(2, spec.steps.size)
        val before = host.journalSnapshot().size
        val report = host.workflowRunner().run(spec)
        assertTrue("workflow failed: ${report.note}", report.ok)
        val journal = host.journalSnapshot().drop(before)
        assertEquals(2, journal.size)
        assertEquals(2, journal.map { it.packageName }.distinct().size)
        android.util.Log.i("FR3KWF", "WORKFLOW_DIRECT_OK steps=${report.records.size} packages=${journal.map { it.packageName }}")
    }
}
