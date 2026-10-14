package com.mcpintelligence.fr3k.capability

import org.json.JSONArray
import org.json.JSONObject

sealed class InverseOp {
    data class ReinstallArchived(val archivePath: String, val sha256: String) : InverseOp()
    /** Undo a reversible bridge change by running the paired capability again. */
    data class RunCapability(val capabilityId: String) : InverseOp()
    data class RestorePrivilegedOp(val packageName: String, val op: String, val mode: String) : InverseOp()
    object NoStateChange : InverseOp()

    fun toJson(): JSONObject = when (this) {
        is ReinstallArchived -> JSONObject().put("kind", "reinstall").put("path", archivePath).put("sha256", sha256)
        is RestorePrivilegedOp -> JSONObject().put("kind", "restore_op").put("packageName", packageName).put("op", op).put("mode", mode)
        is RunCapability -> JSONObject().put("kind", "run_capability").put("capabilityId", capabilityId)
        NoStateChange -> JSONObject().put("kind", "none")
    }

    companion object {
        fun fromJson(json: JSONObject): InverseOp = when (json.getString("kind")) {
            "reinstall" -> ReinstallArchived(json.getString("path"), json.getString("sha256"))
            "restore_op" -> RestorePrivilegedOp(json.getString("packageName"), json.getString("op"), json.getString("mode"))
            "run_capability" -> RunCapability(json.getString("capabilityId"))
            "none" -> NoStateChange
            else -> throw IllegalArgumentException("unknown inverse kind")
        }
    }
}

enum class EvidenceStatus { SUCCEEDED, FAILED, REFUSED }

data class EvidenceRecord(
    val operationId: String,
    val capabilityId: String,
    val appFingerprint: String,
    val packageName: String,
    val startedAtMillis: Long,
    val durationMs: Long,
    val status: EvidenceStatus,
    val beforeSha256: String?,
    val afterSha256: String?,
    val inverse: InverseOp,
    val note: String,
) {
    val undoable: Boolean get() = status == EvidenceStatus.SUCCEEDED && inverse != InverseOp.NoStateChange

    fun toJson(): JSONObject = JSONObject().apply {
        put("operationId", operationId)
        put("capabilityId", capabilityId)
        put("appFingerprint", appFingerprint)
        put("packageName", packageName)
        put("startedAtMillis", startedAtMillis)
        put("durationMs", durationMs)
        put("status", status.name)
        put("beforeSha256", beforeSha256 ?: "")
        put("afterSha256", afterSha256 ?: "")
        put("inverse", inverse.toJson())
        put("note", note)
    }

    companion object {
        fun fromJson(json: JSONObject): EvidenceRecord = EvidenceRecord(
            operationId = json.getString("operationId"),
            capabilityId = json.getString("capabilityId"),
            appFingerprint = json.getString("appFingerprint"),
            packageName = json.getString("packageName"),
            startedAtMillis = json.getLong("startedAtMillis"),
            durationMs = json.getLong("durationMs"),
            status = EvidenceStatus.valueOf(json.getString("status")),
            beforeSha256 = json.optString("beforeSha256").ifEmpty { null },
            afterSha256 = json.optString("afterSha256").ifEmpty { null },
            inverse = InverseOp.fromJson(json.getJSONObject("inverse")),
            note = json.optString("note"),
        )
    }
}

/** Append-only in-memory journal; the app persists it as JSON next to the archive. */
class Journal(initial: List<EvidenceRecord> = emptyList()) {
    private val entries = ArrayList<EvidenceRecord>(initial)

    val records: List<EvidenceRecord> get() = java.util.Collections.unmodifiableList(entries)

    fun append(record: EvidenceRecord) {
        entries.add(record)
    }

    fun recordsFor(packageName: String): List<EvidenceRecord> = entries.filter { it.packageName == packageName }

    fun lastUndoable(packageName: String): EvidenceRecord? = entries.lastOrNull { it.undoable && it.packageName == packageName }

    fun toJson(): JSONArray = JSONArray().also { arr -> entries.forEach { arr.put(it.toJson()) } }

    companion object {
        fun fromJson(arr: JSONArray): Journal =
            Journal((0 until arr.length()).map { EvidenceRecord.fromJson(arr.getJSONObject(it)) })
    }
}
