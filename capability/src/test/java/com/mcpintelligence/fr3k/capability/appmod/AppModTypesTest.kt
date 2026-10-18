package com.mcpintelligence.fr3k.capability.appmod

import com.mcpintelligence.fr3k.capability.AppIdentity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppModTypesTest {

    private fun appIdentity(
        pkg: String = "com.example.app",
        version: Long = 7L,
    ) = AppIdentity(pkg, version, "a".repeat(64), listOf("b".repeat(64), "c".repeat(64)))

    private fun artifact(sourceKind: SourceKind = SourceKind.INSTALLED) = ArtifactIdentity(
        identity = appIdentity(),
        sourceKind = sourceKind,
        capturedAtMillis = 1_700_000_000_000L,
        importedOrigin = if (sourceKind == SourceKind.INSTALLED) null else "content://documents/example.apk",
    )

    private fun evidence(id: String = "ev1", kind: EvidenceKind = EvidenceKind.OBSERVED) = FeatureEvidence(
        evidenceId = id,
        kind = kind,
        feature = "playback speed control",
        reference = EvidenceReference.Resource("0x7f0a0001"),
    )

    private fun report(completed: Boolean = true) = AnalysisReport(
        analysisId = "a1",
        artifact = artifact(),
        analyzedAtMillis = 1_700_000_000_100L,
        technology = setOf(TechnologyIndicator.JAVA_KOTLIN, TechnologyIndicator.NATIVE_LIBRARY),
        evidence = listOf(evidence(), evidence("ev2", EvidenceKind.INFERRED)),
        completed = completed,
        limits = if (completed) emptyList() else listOf("64 MiB per-entry budget reached"),
    )

    private fun suggestion(readiness: Readiness = Readiness.READY) = ModificationSuggestion(
        suggestionId = "s1",
        analysisId = "a1",
        provenance = ProvenanceSource.COMPATIBLE_RECIPE,
        title = "Keep controls visible",
        proposedChange = "Persist the playback speed control during background playback",
        expectedBenefit = "Controls remain reachable without reopening the player",
        evidenceIds = listOf("ev1"),
        requestRefs = listOf("https://github.com/TeamNewPipe/NewPipe/issues/13461"),
        route = ExecutionRoute.APK_PATCH,
        readiness = readiness,
        relevanceConfidence = 0.9,
        executionConfidence = 0.8,
        compatibilityLimits = listOf("exact build fingerprint required"),
        permissions = emptyList(),
        installationConsequences = listOf("re-signed build"),
        recovery = "reinstall the archived original",
    )

    private fun patchSpec() = PatchSpec(
        specId = "spec1",
        suggestionId = "s1",
        identityFingerprint = "d".repeat(64),
        changes = listOf(
            PatchChange.ReplaceResourceString("0x7f0a0001", "old text", "new text"),
            PatchChange.ReplaceDexConstant("Lcom/example/Player;->SPEED:J", "60", "75"),
        ),
        preconditions = listOf(PatchPrecondition("c1", "exact_match_count", "0x7f0a0001", "1")),
        dependencies = emptyList(),
        conflicts = listOf("s2"),
    )

    @Test
    fun artifactIdentity_requiresOriginForImported() {
        try {
            ArtifactIdentity(appIdentity(), SourceKind.IMPORTED_APK, 1L, null)
            fail("imported artifact without origin must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun artifactIdentity_roundTripsJson() {
        val original = artifact(SourceKind.IMPORTED_SPLIT_SET)
        val parsed = ArtifactIdentity.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original, parsed)
        assertEquals(original.fingerprint, parsed.fingerprint)
    }

    @Test
    fun report_requiresLimitsWhenPartial() {
        try {
            AnalysisReport("a1", artifact(), 1L, emptySet(), emptyList(), completed = false, limits = emptyList())
            fail("partial report without limits must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun report_roundTripsJson() {
        val original = report()
        assertEquals(original, AnalysisReport.fromJson(JSONObject(original.toJson().toString())))
    }

    @Test
    fun suggestion_rejectsConfidenceOutOfRange() {
        try {
            suggestion().copy(relevanceConfidence = 1.5)
            fail("relevanceConfidence > 1 must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            suggestion().copy(executionConfidence = -0.1)
            fail("executionConfidence < 0 must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun suggestion_roundTripsJson() {
        val original = suggestion(Readiness.NEEDS_ADAPTER)
        assertEquals(original, ModificationSuggestion.fromJson(JSONObject(original.toJson().toString())))
    }

    @Test
    fun patchSpec_rejectsEmptyChanges() {
        try {
            patchSpec().copy(changes = emptyList())
            fail("empty changes must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun patchSpec_roundTripsJson() {
        val original = patchSpec()
        assertEquals(original, PatchSpec.fromJson(JSONObject(original.toJson().toString())))
    }

    @Test
    fun job_rejectsBackwardsTimestamp() {
        try {
            ModificationJob(
                "j1", artifact(), ModificationStage.QUEUED, 100L, 99L, null, null, null, null, null,
                VerificationStatus.UNVERIFIED, InstallationStatus.NOT_ATTEMPTED, null,
            )
            fail("backwards timestamp must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun job_roundTripsJson() {
        val original = ModificationJob(
            jobId = "j1",
            artifact = artifact(),
            stage = ModificationStage.PREPARED,
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
            analysisId = "a1",
            previewId = "p1",
            patchSpec = patchSpec(),
            outputApkPath = "/shares/out.apk",
            outputSha256 = "e".repeat(64),
            verification = VerificationStatus.STATIC_CHECKS_PASSED,
            installerStatus = InstallationStatus.NOT_ATTEMPTED,
            error = null,
        )
        val parsed = ModificationJob.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original, parsed)
        assertNull(parsed.error)
    }

    @Test
    fun job_terminalAndResumableFlags() {
        val prepared = ModificationJob(
            "j1", artifact(), ModificationStage.PREPARED, 1L, 1L, "a1", null, null, null, null,
            VerificationStatus.STATIC_CHECKS_PASSED, InstallationStatus.NOT_ATTEMPTED, null,
        )
        assertFalse(prepared.terminal)
        assertFalse(prepared.resumable) // prepared holds a completed artifact; nothing to resume

        val interrupted = prepared.copy(stage = ModificationStage.ANALYZING)
        assertTrue(interrupted.resumable)

        val failed = prepared.copy(stage = ModificationStage.FAILED, error = "boom")
        assertTrue(failed.terminal)
        assertFalse(failed.resumable)
    }
}
