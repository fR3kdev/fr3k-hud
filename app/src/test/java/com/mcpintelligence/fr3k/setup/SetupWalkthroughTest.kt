package com.mcpintelligence.fr3k.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupWalkthroughTest {

    private val nothingDone = SetupFacts(
        microphone = false, notifications = false, overlay = false, tts = false,
        recogniser = false, provider = false,
    )

    private val allDone = SetupFacts(
        microphone = true, notifications = true, overlay = true, tts = true,
        recogniser = true, provider = true, developerOptions = true, wirelessDebugging = true,
        termuxInstalled = true, termuxApi = true, termuxBoot = true, termuxStorage = true,
        shizukuRunning = true, batteryUnrestricted = true, voiceTuned = true,
    )

    @Test fun asksForTheFirstThingThatIsActuallyMissing() {
        assertEquals("microphone", SetupWalkthrough.next(emptyMap(), nothingDone)?.id)
        assertEquals("tts", SetupWalkthrough.next(mapOf("microphone" to SetupStatus.DONE), nothingDone)?.id)
    }

    @Test fun satisfiedItemsAreNeverAsked() {
        val facts = nothingDone.copy(microphone = true, tts = true, recogniser = true, voiceTuned = true)
        assertEquals("provider", SetupWalkthrough.next(emptyMap(), facts)?.id)
        assertNull("a fully set-up device has nothing to ask", SetupWalkthrough.next(emptyMap(), allDone))
    }

    @Test fun skippedItemsAreRememberedAndNotRepeated() {
        val progress = mapOf("microphone" to SetupStatus.SKIPPED)
        assertEquals("tts", SetupWalkthrough.next(progress, nothingDone)?.id)
        val pending = SetupWalkthrough.pending(progress, nothingDone)
        assertFalse("a skipped item must never come back", pending.any { it.id == "microphone" })
    }

    @Test fun wirelessDebuggingIsNotOfferedWithoutDeveloperOptions() {
        val facts = nothingDone.copy(microphone = true, tts = true, recogniser = true, provider = true,
            notifications = true, overlay = true, developerOptions = false, voiceTuned = true)
        val pending = SetupWalkthrough.pending(emptyMap(), facts)
        assertEquals("developer_options", pending.first().id)
        // Skipping developer options must silence its children too.
        val skipped = mapOf("developer_options" to SetupStatus.SKIPPED)
        val after = SetupWalkthrough.pending(skipped, facts)
        assertFalse(after.any { it.id == "wireless_debugging" })
    }

    @Test fun skippedDeveloperOptionsStillOffersUnrelatedOptionalItems() {
        val facts = nothingDone.copy(microphone = true, tts = true, recogniser = true, provider = true,
            notifications = true, overlay = true, developerOptions = false, voiceTuned = true)
        val pending = SetupWalkthrough.pending(mapOf("developer_options" to SetupStatus.SKIPPED), facts)
        assertTrue(pending.any { it.id == "termux" })
        assertTrue(pending.any { it.id == "battery" })
    }

    @Test fun providerKeyCannotBeSkipped() {
        val provider = SetupWalkthrough.items.single { it.id == "provider" }
        assertFalse("without a provider the assistant cannot answer at all", SetupWalkthrough.canSkip(provider))
    }

    @Test fun onlyOptionalItemsCanBeSkipped() {
        val microphone = SetupWalkthrough.items.single { it.id == "microphone" }
        val termux = SetupWalkthrough.items.single { it.id == "termux" }
        assertFalse(SetupWalkthrough.canSkip(microphone))
        assertTrue(SetupWalkthrough.canSkip(termux))
    }

    @Test fun spokenPlanCountsWhatIsLeftAndLeadsWithTheNextStep() {
        val facts = nothingDone.copy(microphone = true, tts = true, recogniser = true, provider = true,
            notifications = true, overlay = true, voiceTuned = true)
        val spoken = SetupWalkthrough.spokenPlan(emptyMap(), facts)!!
        assertTrue(spoken.contains("setup steps are left") || spoken.contains("One setup step is left"))
        assertTrue(spoken.contains("Developer options"))
        assertNull(SetupWalkthrough.spokenPlan(emptyMap(), allDone))
    }

    @Test fun aDeviceWithNoRootSupportCanSkipShizukuStyleStepsForever() {
        // Developer options unavailable on a managed/locked device: mark skipped, then never ask.
        val blocked = mapOf("developer_options" to SetupStatus.SKIPPED, "wireless_debugging" to SetupStatus.SKIPPED)
        val facts = nothingDone.copy(microphone = true, tts = true, recogniser = true, provider = true,
            notifications = true, overlay = true, voiceTuned = true)
        val pending = SetupWalkthrough.pending(blocked, facts)
        assertFalse(pending.any { it.needsDeveloperOptions })
    }
}
