package com.mcpintelligence.fr3k.appcontrol.analysis

import com.mcpintelligence.fr3k.capability.AppIdentity
import com.mcpintelligence.fr3k.capability.appmod.ArtifactIdentity
import com.mcpintelligence.fr3k.capability.appmod.SourceKind
import com.mcpintelligence.fr3k.capability.appmod.TechnologyIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkAnalyzerTest {

    private fun fixtureApk(): File {
        val candidates = listOf(
            File("src/main/assets/app-control/owned-fixture.apk"),
            File("app/src/main/assets/app-control/owned-fixture.apk"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("owned-fixture.apk not found; run this test from the app module directory")
    }

    private fun artifact() = ArtifactIdentity(
        identity = AppIdentity(
            "com.mcpintelligence.fr3k.patchfixture",
            1L,
            "a".repeat(64),
            listOf("b".repeat(64)),
        ),
        sourceKind = SourceKind.INSTALLED,
        capturedAtMillis = 1_700_000_000_000L,
        importedOrigin = null,
    )

    @Test
    fun analyzesTheOwnedFixtureApk() {
        val report = ApkAnalyzer.analyze(fixtureApk(), artifact(), "a-test-1")
        if (!report.completed) System.err.println("ANALYSIS LIMITS: ${report.limits}")

        assertTrue("report should complete with a readable DEX", report.completed)
        assertTrue("technology should include JAVA_KOTLIN", TechnologyIndicator.JAVA_KOTLIN in report.technology)
        assertTrue("evidence should not be empty", report.evidence.isNotEmpty())
        assertTrue(
            "should contain a dex-census observation",
            report.evidence.any { it.evidenceId == "dex-census" },
        )
        assertTrue(
            "should contain a class-sample reference",
            report.evidence.any { it.evidenceId == "class-sample" },
        )
        assertTrue(
            "should extract the manifest summary",
            report.evidence.any { it.evidenceId == "manifest" },
        )
        assertTrue(
            "should extract declared components",
            report.evidence.any { it.evidenceId == "components" },
        )
        assertTrue(
            "should record analysis coverage bounds",
            report.limits.any { it.contains("Resources capped") },
        )
        assertEquals(artifact(), report.artifact)
    }

    @Test
    fun rejectsUnreadableApk() {
        try {
            ApkAnalyzer.analyze(File("/nonexistent/apk.apk"), artifact(), "a-test-2")
            org.junit.Assert.fail("expected an exception for a missing APK")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun reportRoundTripsThroughJson() {
        val report = ApkAnalyzer.analyze(fixtureApk(), artifact(), "a-test-3")
        val parsed = com.mcpintelligence.fr3k.capability.appmod.AnalysisReport.fromJson(
            org.json.JSONObject(report.toJson().toString()),
        )
        assertEquals(report, parsed)
        assertFalse(report.limits.isEmpty())
    }
}
