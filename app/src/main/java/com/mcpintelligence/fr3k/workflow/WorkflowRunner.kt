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

    suspend fun run(spec: WorkflowSpec, onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }): Report {
        val registry = host.registry()
        val installed = host.installed()
        val bound = try {
            WorkflowBinder.bind(spec, registry, installed)
        } catch (refused: WorkflowBindException) {
            return Report(spec.id, false, emptyList(), refused.message ?: "binding failed")
        }
        val approved = bound.map { it.descriptor.identity.packageName }.toSet()
        val executor by lazy { BridgeStepExecutor(Fr3kBridgeService.require(), approved, privileged) }
        val allRecords = ArrayList<StepRecord>()
        val journalIds = ArrayList<String>()
        bound.forEachIndexed { index, step ->
            onProgress(index + 1, bound.size, step.descriptor.id)
            val started = SystemClock.elapsedRealtime()
            val before = Fr3kBridgeService.instanceOrNull()?.foregroundPackage()
            val records = try {
                if (step.steps.all { it is Step.PrivilegedOp }) {
                    step.steps.mapIndexed { ordinal, action ->
                        action as Step.PrivilegedOp
                        try { StepRecord(ordinal, "privileged_op", "OK", privileged(action.packageName, action.op, action.mode)) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { throw com.mcpintelligence.fr3k.bridge.StepFailedException(StepRecord(ordinal,"privileged_op","FAILED",e.message ?: "Operation failed")) }
                    }
                } else executor.execute(step.steps)
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
            val inverse = inverseFor(step.descriptor, records)
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

    fun inverseFor(descriptor: CapabilityDescriptor, records: List<StepRecord> = emptyList()): InverseOp {
        val mutation = descriptor.steps.filterIsInstance<Step.PrivilegedOp>().singleOrNull { it.mode != "read" }
        if (mutation != null) {
            val before = records.asReversed().firstNotNullOfOrNull { record ->
                Regex("op=" + Regex.escape(mutation.op) + " ([a-z_]+)->([a-z_]+)").find(record.detail)?.groupValues?.get(1)
            }
            if (before != null && runCatching { PrivilegedOps.requireMode(before) }.isSuccess)
                return InverseOp.RestorePrivilegedOp(mutation.packageName, mutation.op, before)
            return InverseOp.NoStateChange
        }
        return when (descriptor.id) {
            "fixture.banner.hide" -> InverseOp.RunCapability("fixture.banner.show")
            "fixture.banner.show" -> InverseOp.RunCapability("fixture.banner.hide")
            "fixture.ad.mask" -> InverseOp.RunCapability("fixture.ad.unmask")
            else -> InverseOp.NoStateChange
        }
    }
}
