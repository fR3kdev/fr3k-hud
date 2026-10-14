package com.mcpintelligence.fr3k.capability

import org.junit.Assert.*
import org.junit.Test

class JournalTest {
    private fun record(
        id: String = "op-1",
        status: EvidenceStatus = EvidenceStatus.SUCCEEDED,
        pkg: String = "com.x.y",
        inverse: InverseOp = InverseOp.RestorePrivilegedOp(pkg, "WAKE_LOCK", "allow"),
    ) = EvidenceRecord(
        operationId = id,
        capabilityId = "demo.op",
        appFingerprint = "f".repeat(64),
        packageName = pkg,
        startedAtMillis = 1_700_000_000_000,
        durationMs = 42,
        status = status,
        beforeSha256 = "a".repeat(64),
        afterSha256 = "b".repeat(64),
        inverse = inverse,
        note = "test",
    )

    @Test fun recordsAreAppendOnlyAndQueryable() {
        val journal = Journal()
        journal.append(record("op-1"))
        journal.append(record("op-2", pkg = "com.z.z"))
        assertEquals(2, journal.records.size)
        assertEquals(1, journal.recordsFor("com.x.y").size)
        assertThrows(UnsupportedOperationException::class.java) { (journal.records as MutableList).clear() }
    }

    @Test fun failedAndNoOpRecordsAreNotUndoable() {
        val journal = Journal()
        journal.append(record("op-1", status = EvidenceStatus.FAILED))
        journal.append(record("op-2", inverse = InverseOp.NoStateChange))
        journal.append(record("op-3"))
        assertNull(journal.lastUndoable("com.z.z"))
        assertEquals("op-3", journal.lastUndoable("com.x.y")!!.operationId)
        assertEquals(1, journal.records.count { it.undoable })
    }

    @Test fun journalRoundTripsThroughJson() {
        val journal = Journal()
        journal.append(record("op-1"))
        journal.append(record("op-2", inverse = InverseOp.ReinstallArchived("/data/x.apk", "c".repeat(64))))
        val restored = Journal.fromJson(journal.toJson())
        assertEquals(journal.records.map { it.operationId }, restored.records.map { it.operationId })
        assertEquals(InverseOp.NoStateChange.toJson().toString(), InverseOp.fromJson(InverseOp.NoStateChange.toJson()).toJson().toString())
    }

    @Test fun unknownInverseKindIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            InverseOp.fromJson(org.json.JSONObject().put("kind", "delete_everything"))
        }
    }
}
