package com.mcpintelligence.fr3k.appcontrol

import com.mcpintelligence.fr3k.capability.ModCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract behind the APP MODS screen: nothing executable may be reachable only by guessing a
 * phrase, and no guide may be a stub. If a capability is added to the catalogue without a guide, this
 * fails the build.
 */
class ModGuideCoverageTest {

    private val registered: Set<String> get() = CapabilityCatalogue.phrases().keys
    private val guides = ModGuideCatalogue.all()

    @Test fun everyRegisteredCapabilityIsExplainedByAGuide() {
        val missing = ModGuideCatalogue.coverage(registered)
        assertTrue("capabilities with no guide: $missing", missing.isEmpty())
    }

    @Test fun noGuideInventsACapabilityThatDoesNotExist() {
        val invented = ModGuideCatalogue.coveredCapabilityIds() - registered
        assertTrue("guides reference unknown capabilities: $invented", invented.isEmpty())
    }

    @Test fun everyCapabilityHasItsOwnCard() {
        // A capability may appear in a procedure guide, but each one also needs a card of its own.
        val ownCards = ModGuideCatalogue.capabilities().flatMap { it.capabilityIds }.toSet()
        val withoutOwnCard = registered - ownCards
        assertTrue("capabilities with no card of their own: $withoutOwnCard", withoutOwnCard.isEmpty())
    }

    @Test fun guideIdsAreUnique() {
        val duplicates = guides.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue("duplicate guide ids: $duplicates", duplicates.isEmpty())
    }

    @Test fun everyGuideIsAWalkthroughNotAPlaceholder() {
        for (guide in guides) {
            assertTrue("guide ${guide.id} needs at least three steps", guide.steps.size >= 3)
            assertTrue("guide ${guide.id} needs a substantive explanation", guide.whatItDoes.length >= 80)
            assertTrue("guide ${guide.id} needs a real limits line", guide.limits.length >= 40)
            assertTrue("guide ${guide.id} needs a real undo line", guide.reversible.length >= 20)
            assertTrue("guide ${guide.id} needs a concrete phrase", guide.examplePhrase.split(" ").size >= 2)
        }
    }

    @Test fun everyCategoryIsActuallyUsed() {
        for (category in ModCategory.entries) {
            assertTrue(
                "category ${category.label} has no guides",
                guides.any { it.category == category },
            )
        }
    }

    @Test fun theCatalogueIsRichEnoughToTeachThePlatform() {
        assertTrue("the catalogue should cover every plane", guides.size >= registered.size + 5)
        assertTrue("more than a handful of walkthrough steps", guides.sumOf { it.steps.size } >= 80)
    }

    @Test fun guidesRoundTripToJsonForAgents() {
        val json = ModGuideCatalogue.toJson(guides)
        assertEquals(guides.size, json.length())
        val first = json.getJSONObject(0)
        assertTrue(first.has("steps"))
        assertTrue(first.getJSONArray("steps").length() >= 3)
        assertTrue(first.has("limits"))
        assertTrue(first.has("examplePhrase"))
    }

    /**
     * The catalogue an operator actually sees. The lab catalogue above proves the fixture contracts; this
     * proves the release journeys are complete in their own right, so a later edit cannot quietly ship a
     * journey with no limits, no undo path, two steps, or a fixture capability bound into it.
     */
    @Test fun everyReleaseJourneyIsCompleteAndNeverFixtureBound() {
        val release = releaseProcedures()
        assertTrue("the release catalogue must not be empty", release.isNotEmpty())
        for (guide in release) {
            assertTrue("${guide.id} needs at least 3 steps", guide.steps.size >= 3)
            assertTrue("${guide.id} needs what-it-does text", guide.whatItDoes.isNotBlank())
            assertTrue("${guide.id} needs a one-line summary", guide.oneLiner.isNotBlank())
            assertTrue("${guide.id} must state its limits", guide.limits.isNotBlank())
            assertTrue("${guide.id} must state an undo or recovery path", guide.reversible.isNotBlank())
            assertTrue("${guide.id} must say where it runs", guide.runsOn.isNotBlank())
            assertTrue(
                "${guide.id} must not be bound to a fixture capability",
                guide.capabilityIds.none { it.startsWith("fixture.") || it.startsWith("target.two") },
            )
            assertTrue(
                "${guide.id} must not claim to work on arbitrary apps",
                !guide.limits.startsWith("Works on any app"),
            )
        }
    }

    @Test fun theTwoOwnedTargetsAreDescribedHonestly() {
        // Guides must not imply the platform reaches apps the operator does not own.
        val fixtureGuides = ModGuideCatalogue.capabilities()
        for (guide in fixtureGuides) {
            assertTrue(
                "guide ${guide.id} must name what it runs on",
                guide.runsOn.contains("com.mcpintelligence.fr3k."),
            )
            assertTrue(
                "guide ${guide.id} must not claim to work on arbitrary apps",
                !guide.limits.startsWith("Works on any app"),
            )
        }
    }
}
