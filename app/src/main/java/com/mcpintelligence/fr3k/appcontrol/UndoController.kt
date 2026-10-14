package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import com.mcpintelligence.fr3k.capability.AppIdentity
import com.mcpintelligence.fr3k.capability.EvidenceRecord
import com.mcpintelligence.fr3k.capability.InverseOp
import java.io.File

class UndoRefusedException(message: String) : IllegalStateException(message)

/**
 * Restores a recorded state. It never installs anything by itself: it resolves and verifies the
 * archived original and hands the user-reviewed installer a file that provably came from this device.
 */
class UndoController(private val context: Context, private val archive: OriginalArchive) {

    data class UndoPlan(val record: EvidenceRecord, val restoreApk: File, val restoreSha256: String, val message: String)

    fun plan(record: EvidenceRecord, currentIdentity: AppIdentity?): UndoPlan {
        if (record.status != com.mcpintelligence.fr3k.capability.EvidenceStatus.SUCCEEDED) {
            throw UndoRefusedException("only a succeeded operation can be undone")
        }
        return when (val inverse = record.inverse) {
            is InverseOp.ReinstallArchived -> {
                val file = File(inverse.archivePath)
                if (!file.isFile) throw UndoRefusedException("archived original is missing for ${record.packageName}")
                val hash = ApkHashing.sha256(file)
                if (hash != inverse.sha256) throw UndoRefusedException("archived original changed since it was recorded")
                if (currentIdentity != null && currentIdentity.packageName != record.packageName) {
                    throw UndoRefusedException("archive belongs to ${record.packageName}, device reports ${currentIdentity.packageName}")
                }
                UndoPlan(record, file, hash, "Review and reinstall the archived original of ${record.packageName}")
            }
            is InverseOp.RestorePrivilegedOp -> UndoPlan(
                record, File("/dev/null"), "",
                "Restore ${inverse.op} to '${inverse.mode}' for ${inverse.packageName}",
            )
            is InverseOp.RunCapability -> UndoPlan(
                record, File("/dev/null"), "",
                "Run the paired capability ${inverse.capabilityId} to reverse ${record.capabilityId}",
            )
            InverseOp.NoStateChange -> throw UndoRefusedException("this operation did not change persistent state")
        }
    }
}
