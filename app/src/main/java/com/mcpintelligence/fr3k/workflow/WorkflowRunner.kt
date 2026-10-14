package com.mcpintelligence.fr3k.workflow

import android.os.SystemClock
import com.mcpintelligence.fr3k.appcontrol.CapabilityHost
import com.mcpintelligence.fr3k.bridge.BridgeStepExecutor
import com.mcpintelligence.fr3k.bridge.Fr3kBridgeService
import com.mcpintelligence.fr3k.bridge.StepRecord
import com.mcpintelligence.fr3k.capability.*

/** Runs a bound workflow: one deterministic pass, one journal entry per capability. */
class WorkflowRunner(
    private val host: CapabilityHost,
    private val privileged: suspend (String, String, String) -> String,
) {

    data class Report(
        val workflowId: String,
        val ok: Boolean,
        val records: List<StepRecord>,
        val note: String,
        val journalIds: List<String> = emptyList(),
    )

    suspend fun run(spec: WorkflowSpec): Report {
        val registry = host.registry()
        val installed = host.installed()
        val bound = try {
            WorkflowBinder.bind(spec, registry, installed)
        } catch (refused: WorkflowBindException) {
            return Report(spec.id, false, emptyList(), refused.message ?: "binding failed")
        }
        val approved = bound.map { it.descriptor.identity.packageName }.toSet()
        val executor = BridgeStepExecutor(Fr3kBridgeService.require(), approved, privileged)
        val allRecords = ArrayList<StepRecord>()
        val journalIds = ArrayList<String>()
        bound.forEach { step ->
            val started = SystemClock.elapsedRealtime()
            val before = runCatching { executor.foregroundPackage() }.getOrNull()
            val records = try {
                executor.execute(step.steps)
            } catch (failed: com.mcpintelligence.fr3k.bridge.StepFailedException) {
                allRecords.add(failed.record)
                host.record(
                    EvidenceRecord(
                        operationId = java.util.UUID.randomUUID().toString(),
                        capabilityId = step.descriptor.id,
                        appFingerprint = step.descriptor.identity.fingerprint,
                        packageName = step.descriptor.identity.packageName,
                        startedAtMillis = System.currentTimeMillis(),
                        durationMs = SystemClock.elapsedRealtime() - started,
                        status = EvidenceStatus.FAILED,
                        beforeSha256 = null,
                        afterSha256 = null,
                        inverse = InverseOp.NoStateChange,
                        note = "${failed.record.op}: ${failed.record.detail} (foreground=$before)",
                    ),
                )
                return Report(spec.id, false, allRecords, "step failed: ${failed.record.detail}")
            }
            allRecords.addAll(records)
            val inverse = inverseFor(step.descriptor)
            val operationId = java.util.UUID.randomUUID().toString()
            journalIds.add(operationId)
            host.record(
                EvidenceRecord(
                    operationId = operationId,
                    capabilityId = step.descriptor.id,
                    appFingerprint = step.descriptor.identity.fingerprint,
                    packageName = step.descriptor.identity.packageName,
                    startedAtMillis = System.currentTimeMillis(),
                    durationMs = SystemClock.elapsedRealtime() - started,
                    status = EvidenceStatus.SUCCEEDED,
                    beforeSha256 = null,
                    afterSha256 = null,
                    inverse = inverse,
                    note = records.joinToString("; ") { "${it.op}:${it.status}" },
                ),
            )
        }
        return Report(spec.id, true, allRecords, "completed ${bound.size} capability step(s)", journalIds)
    }

    private fun inverseFor(descriptor: CapabilityDescriptor): InverseOp = when (descriptor.id) {
        "fixture.banner.hide" -> InverseOp.RunCapability("fixture.banner.show")
        "fixture.banner.show" -> InverseOp.RunCapability("fixture.banner.hide")
        "fixture.ad.mask" -> InverseOp.RunCapability("fixture.ad.unmask")
        "fixture.policy.deny_wake_lock" -> InverseOp.RestorePrivilegedOp(
            descriptor.identity.packageName, "WAKE_LOCK", "allow",
        )
        "fixture.policy.allow_wake_lock" -> InverseOp.RestorePrivilegedOp(
            descriptor.identity.packageName, "WAKE_LOCK", "deny",
        )
        else -> InverseOp.NoStateChange
    }
}
