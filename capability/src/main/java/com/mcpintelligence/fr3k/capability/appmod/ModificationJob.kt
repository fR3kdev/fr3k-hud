package com.mcpintelligence.fr3k.capability.appmod

import org.json.JSONObject

/**
 * Durable stage of one end-to-end modification job. Stages before [PREPARED] are not yet
 * installable; [INTERRUPTED] is the marker written when a process dies mid-stage so the next
 * launch can resume from a verified checkpoint instead of guessing.
 */
enum class ModificationStage {
    QUEUED, ANALYZING, SUGGESTING, SELECTING, PREVIEWING, PREPARING, PREPARED,
    INSTALLING, INSTALLED, VERIFYING, COMPLETED, FAILED, CANCELLED, INTERRUPTED,
}

/** What Android actually reported about the install attempt, kept separate from static checks. */
enum class InstallationStatus { NOT_ATTEMPTED, INSTALLER_OPENED, INSTALLED, ORIGINAL_RESTORED, CANCELLED, FAILED }

/** Verification is a ladder: static checks pass before a behavior test, then a manual observation. */
enum class VerificationStatus { UNVERIFIED, STATIC_CHECKS_PASSED, BEHAVIOR_VERIFIED, USER_CONFIRMED }

/**
 * The durable record of a job. Artifact references are ids/hashes/paths, never in-memory handles,
 * so the record survives navigation, process recreation and device reboot. [artifact] may be null
 * only while the job is [ModificationStage.QUEUED] and a target has not been selected yet.
 */
data class ModificationJob(
    val jobId: String,
    val artifact: ArtifactIdentity?,
    val stage: ModificationStage,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val analysisId: String?,
    val previewId: String?,
    val patchSpec: PatchSpec?,
    val outputApkPath: String?,
    val outputSha256: String?,
    val verification: VerificationStatus,
    val installerStatus: InstallationStatus,
    val error: String?,
) {
    init {
        require(jobId.isNotBlank()) { "jobId is required" }
        require(createdAtMillis > 0) { "createdAtMillis must be positive" }
        require(updatedAtMillis >= createdAtMillis) { "updatedAtMillis must not precede createdAtMillis" }
        if (outputSha256 != null) require(outputSha256.matches(Regex("[0-9a-f]{64}"))) { "outputSha256 must be a SHA-256 hex digest" }
    }

    val terminal: Boolean
        get() = stage == ModificationStage.COMPLETED || stage == ModificationStage.FAILED ||
            stage == ModificationStage.CANCELLED

    /** True when a process death interrupted a stage that was still doing work. */
    val resumable: Boolean
        get() = !terminal && stage != ModificationStage.PREPARED && stage != ModificationStage.INSTALLED

    fun toJson(): JSONObject = JSONObject().apply {
        put("jobId", jobId)
        put("artifact", artifact?.toJson()?.toString() ?: "")
        put("stage", stage.name)
        put("createdAtMillis", createdAtMillis)
        put("updatedAtMillis", updatedAtMillis)
        put("analysisId", analysisId ?: "")
        put("previewId", previewId ?: "")
        put("patchSpec", patchSpec?.toJson()?.toString() ?: "")
        put("outputApkPath", outputApkPath ?: "")
        put("outputSha256", outputSha256 ?: "")
        put("verification", verification.name)
        put("installerStatus", installerStatus.name)
        put("error", error ?: "")
    }

    companion object {
        fun fromJson(json: JSONObject): ModificationJob = ModificationJob(
            jobId = json.getString("jobId"),
            artifact = json.optString("artifact").ifEmpty { null }?.let { ArtifactIdentity.fromJson(JSONObject(it)) },
            stage = ModificationStage.valueOf(json.getString("stage")),
            createdAtMillis = json.getLong("createdAtMillis"),
            updatedAtMillis = json.getLong("updatedAtMillis"),
            analysisId = json.optString("analysisId").ifEmpty { null },
            previewId = json.optString("previewId").ifEmpty { null },
            patchSpec = json.optString("patchSpec").ifEmpty { null }?.let { PatchSpec.fromJson(JSONObject(it)) },
            outputApkPath = json.optString("outputApkPath").ifEmpty { null },
            outputSha256 = json.optString("outputSha256").ifEmpty { null },
            verification = VerificationStatus.valueOf(json.getString("verification")),
            installerStatus = InstallationStatus.valueOf(json.getString("installerStatus")),
            error = json.optString("error").ifEmpty { null },
        )
    }
}
