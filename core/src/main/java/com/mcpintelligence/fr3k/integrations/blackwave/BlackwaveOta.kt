package com.mcpintelligence.fr3k.integrations.blackwave

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * Client-side view of a wireless (OTA) update. The HUD only stages a signed
 * A/B request with the fleet bridge and mirrors the gateway's reported stage;
 * it never flashes, reboots or rolls back a target itself.
 *
 * Mirrors the android-companion stage model (`core/ota/OtaController` +
 * `core/evidence/OtaPresentation`).
 */
enum class OtaStatus {
    IDLE,
    REQUESTED,
    RUNNING,
    COMMITTED,
    FAILED,
    ROLLED_BACK,
    RECOVERY_REQUIRED;

    val active: Boolean get() = this == REQUESTED || this == RUNNING
    val terminal: Boolean get() = this == COMMITTED || this == FAILED ||
        this == ROLLED_BACK || this == RECOVERY_REQUIRED
}

/** Progress snapshot for one staged update. */
data class OtaProgress(
    val jobId: String? = null,
    val deviceId: String? = null,
    val modelId: String? = null,
    val stageIndex: Int = -1,
    val stageLabel: String = "No update requested",
    val status: OtaStatus = OtaStatus.IDLE,
    val committed: Boolean = false,
    val detail: String? = null,
    val targetFirmware: String? = null,
    val simulation: Boolean = false,
) {
    val active: Boolean get() = status.active
    val terminal: Boolean get() = status.terminal

    /** Fail-closed: success is only claimed after the pipeline completed and the target committed. */
    val success: Boolean get() = OtaPresentation.successAllowed(stageLabel, committed)
}

/** Presentation model shared by the progress UI and the gateway parser. */
object OtaPresentation {
    val STAGES: List<String> = listOf(
        "Checking manifest",
        "Authenticating",
        "Downloading",
        "Verifying hash",
        "Preparing candidate",
        "Booting candidate",
        "Health checking",
        "Committing",
        "Complete",
    )

    val FAILURES: List<String> = listOf(
        "Rejected",
        "Download failed",
        "Verification failed",
        "Candidate unhealthy",
        "Rollback initiated",
        "Rollback complete",
        "Recovery required",
    )

    fun successAllowed(stage: String, committed: Boolean): Boolean =
        stage == "Complete" && committed
}

/**
 * Wire protocol for the canonical mobile OTA staging API. The client POSTs a
 * staging request and then polls the resulting job. Every parse failure is
 * surfaced as a failed [Result] so the caller fails closed.
 */
object BlackwaveOta {
    const val STAGE_PATH: String = "/mobile/v1/ota/stage"
    const val JOBS_PATH: String = "/mobile/v1/ota/jobs"
    const val SIMULATED_FIRMWARE: String = "sim-9.9.9"

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun jobPath(jobId: String): String = "$JOBS_PATH/${URLEncoder.encode(jobId, "UTF-8")}"

    fun stageBody(
        deviceId: String,
        modelId: String,
        otaStrategy: String = "ab",
        flashBackend: String = "gateway",
    ): String = buildJsonObject {
        put("device_id", deviceId)
        put("model_id", modelId)
        put("ota_strategy", otaStrategy)
        put("flash_backend", flashBackend)
    }.toString()

    /** Parse a stage/poll job payload into a fail-closed [OtaProgress]. */
    fun parse(
        body: String,
        deviceId: String?,
        modelId: String?,
        simulation: Boolean = false,
    ): OtaProgress {
        val wire = json.decodeFromString<OtaJobWire>(body)
        val stageLabel = wire.stage?.takeIf { it.isNotBlank() } ?: OtaPresentation.STAGES.first()
        val committed = wire.committed
        val status = when {
            wire.recovery_required -> OtaStatus.RECOVERY_REQUIRED
            wire.rollback -> OtaStatus.ROLLED_BACK
            wire.failed -> OtaStatus.FAILED
            committed && OtaPresentation.successAllowed(stageLabel, committed) -> OtaStatus.COMMITTED
            else -> OtaStatus.RUNNING
        }
        return OtaProgress(
            jobId = wire.job_id?.takeIf { it.isNotBlank() },
            deviceId = deviceId,
            modelId = modelId,
            stageIndex = OtaPresentation.STAGES.indexOf(stageLabel),
            stageLabel = stageLabel,
            status = status,
            committed = committed,
            detail = wire.detail?.takeIf { it.isNotBlank() } ?: stageLabel,
            targetFirmware = wire.target_firmware?.takeIf { it.isNotBlank() },
            simulation = simulation,
        )
    }
}

/** Raw stage/poll job payload as served by the fleet bridge. */
@Serializable
internal data class OtaJobWire(
    val job_id: String? = null,
    val stage: String? = null,
    val committed: Boolean = false,
    val failed: Boolean = false,
    val rollback: Boolean = false,
    val recovery_required: Boolean = false,
    val detail: String? = null,
    val target_firmware: String? = null,
)
