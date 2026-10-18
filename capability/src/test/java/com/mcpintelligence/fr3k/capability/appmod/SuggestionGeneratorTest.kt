package com.mcpintelligence.fr3k.capability.appmod

import com.mcpintelligence.fr3k.capability.AppIdentity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionGeneratorTest {

    private fun artifact() = ArtifactIdentity(
        identity = AppIdentity("com.example.app", 1L, "a".repeat(64), listOf("b".repeat(64))),
        sourceKind = SourceKind.INSTALLED,
        capturedAtMillis = 1L,
        importedOrigin = null,
    )

    private fun report(vararg tech: TechnologyIndicator) = AnalysisReport(
        analysisId = "a1",
        artifact = artifact(),
        analyzedAtMillis = 2L,
        technology = tech.toSet(),
        evidence = tech.mapIndexed { i, t -> FeatureEvidence("ev$i", EvidenceKind.OBSERVED, t.name, EvidenceReference.None) },
        completed = true,
        limits = emptyList(),
    )

    @Test
    fun alwaysOffersShareAndPreferencePersistence() {
        val ids = SuggestionGenerator.generate(report()).map { it.suggestionId }.toSet()
        assertTrue("share.integrate should always be offered", "purpose.share.integrate" in ids)
        assertTrue("prefs.persist should always be offered", "purpose.prefs.persist" in ids)
    }

    @Test
    fun webViewYieldsUrlCleanup() {
        val suggestions = SuggestionGenerator.generate(report(TechnologyIndicator.WEBVIEW))
        val url = suggestions.single { it.suggestionId == "purpose.url.clean" }
        assertEquals(ExecutionRoute.HUD_INTEGRATION, url.route)
        assertEquals(Readiness.CAN_GENERATE, url.readiness)
    }

    @Test
    fun nativeLibraryYieldsUnsupportedMetadataLimit() {
        val suggestions = SuggestionGenerator.generate(report(TechnologyIndicator.NATIVE_LIBRARY))
        val limit = suggestions.single { it.suggestionId == "purpose.native.limit" }
        assertEquals(Readiness.UNSUPPORTED, limit.readiness)
    }

    @Test
    fun neverEmitsReadyOrRecipeClaims() {
        val suggestions = SuggestionGenerator.generate(
            report(TechnologyIndicator.WEBVIEW, TechnologyIndicator.NATIVE_LIBRARY, TechnologyIndicator.OBFUSCATED),
        )
        assertTrue(
            "purpose-based generation must never claim READY or a compatible recipe",
            suggestions.none { it.readiness == Readiness.READY || it.provenance == ProvenanceSource.COMPATIBLE_RECIPE },
        )
    }

    @Test
    fun everySuggestionIsBoundToTheAnalysisAndRoundTrips() {
        val suggestions = SuggestionGenerator.generate(
            report(TechnologyIndicator.WEBVIEW, TechnologyIndicator.FLUTTER, TechnologyIndicator.OBFUSCATED),
        )
        assertTrue(suggestions.isNotEmpty())
        suggestions.forEach { s ->
            assertEquals("a1", s.analysisId)
            assertEquals(ProvenanceSource.PURPOSE_BASED, s.provenance)
            assertEquals(s, ModificationSuggestion.fromJson(JSONObject(s.toJson().toString())))
        }
    }
}
