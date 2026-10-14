package com.mcpintelligence.fr3k.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModGuideTest {

    private fun guide(
        id: String = "g.one",
        requirements: List<ModRequirement> = listOf(ModRequirement.BRIDGE),
        category: ModCategory = ModCategory.AUTOMATE,
    ) = ModGuide(
        id = id,
        title = "A guide",
        category = category,
        oneLiner = "does a thing",
        whatItDoes = "does the thing properly",
        requirements = requirements,
        steps = listOf("first", "second"),
        examplePhrase = "do the thing",
        capabilityIds = listOf("fixture.demo.open"),
        runsOn = "the owned fixture",
        limits = "only the owned fixture",
        reversible = "undo from APP CONTROL",
    )

    @Test fun aSingleStepIsNotAWalkthrough() {
        val bad = runCatching { guide().copy(steps = listOf("only one step")) }.exceptionOrNull()
        assertTrue("a single-step guide is not a walkthrough", bad is IllegalArgumentException)
    }

    @Test fun blankEssentialTextIsRefused() {
        val cases = listOf<() -> ModGuide>(
            { guide().copy(title = " ") },
            { guide().copy(oneLiner = "") },
            { guide().copy(whatItDoes = "") },
            { guide().copy(examplePhrase = "  ") },
            { guide().copy(runsOn = "") },
            { guide().copy(limits = "") },
            { guide().copy(reversible = "") },
            { guide().copy(id = " ") },
            { guide().copy(steps = emptyList()) },
        )
        for (case in cases) {
            assertFalse("blank essential text must be refused", runCatching { case() }.isSuccess)
        }
    }

    @Test fun aBridgeGuideNeedsTheBridgeBound() {
        val target = guide()
        val unbound = ModEnvironment(bridgeBound = false)
        assertEquals(ModReadiness.NEEDS_SETUP, ModGuideStatus.readiness(target, unbound))
        assertEquals(listOf(ModRequirement.BRIDGE), ModGuideStatus.missing(target, unbound))
        assertEquals(ModReadiness.READY, ModGuideStatus.readiness(target, ModEnvironment(bridgeBound = true)))
        assertTrue(ModGuideStatus.missing(target, ModEnvironment(bridgeBound = true)).isEmpty())
    }

    @Test fun theShellTierSatisfiesTheAdbBootstrapButNotRoot() {
        val privilegedGuide = guide(requirements = listOf(ModRequirement.ADB_BOOTSTRAP, ModRequirement.ROOT))
        val shellOnly = ModEnvironment(bridgeBound = true, privilegedShell = true)
        assertEquals(ModReadiness.NEEDS_SETUP, ModGuideStatus.readiness(privilegedGuide, shellOnly))
        assertEquals(listOf(ModRequirement.ROOT), ModGuideStatus.missing(privilegedGuide, shellOnly))

        val asRoot = ModEnvironment(bridgeBound = true, privilegedShell = true, privilegedRoot = true)
        assertEquals(ModReadiness.READY, ModGuideStatus.readiness(privilegedGuide, asRoot))
    }

    @Test fun rootAlsoSatisfiesTheBootstrapBecauseItIsTheSameService() {
        val bootstrap = guide(requirements = listOf(ModRequirement.ADB_BOOTSTRAP))
        val asRoot = ModEnvironment(privilegedRoot = true)
        assertEquals(ModReadiness.READY, ModGuideStatus.readiness(bootstrap, asRoot))
    }

    @Test fun owningATargetIsTheOperatorsDecisionNotTheDevicesState() {
        val owned = guide(requirements = listOf(ModRequirement.OWNED_TARGET))
        assertTrue(ModGuideStatus.missing(owned, ModEnvironment()).isEmpty())
        assertEquals(ModReadiness.READY, ModGuideStatus.readiness(owned, ModEnvironment()))
    }

    @Test fun guidesAreOrderedUsefulFirst() {
        val ready = guide(id = "a.ready", requirements = listOf(ModRequirement.OWNED_TARGET), category = ModCategory.MODIFY)
        val notReady = guide(id = "b.notready", requirements = listOf(ModRequirement.ROOT), category = ModCategory.INSPECT)
        val ordered = ModGuideStatus.order(
            listOf(notReady, ready),
            ModEnvironment(bridgeBound = false, privilegedShell = false, privilegedRoot = false),
        )
        assertEquals(listOf("a.ready", "b.notready"), ordered.map { it.id })
    }
}
