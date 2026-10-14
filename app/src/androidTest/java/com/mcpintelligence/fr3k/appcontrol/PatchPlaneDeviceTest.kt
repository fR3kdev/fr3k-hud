package com.mcpintelligence.fr3k.appcontrol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.capability.EvidenceRecord
import com.mcpintelligence.fr3k.capability.EvidenceStatus
import com.mcpintelligence.fr3k.capability.InverseOp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The patch mechanic against a real installed app the operator owns, with a tested undo. */
@RunWith(AndroidJUnit4::class)
class PatchPlaneDeviceTest {

    private val target = "com.mcpintelligence.fr3k.patchfixturetwo"

    @Test fun patchesAUsersOwnInstalledApkSignsItArchivesTheOriginalAndPlansUndo() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = TargetResolver(context)
        val original = resolver.identityOf(target)
        val workspace = File(context.noBackupFilesDir, "app-control/workspace").apply { mkdirs() }
        val staged = resolver.stage(target, workspace)
        assertEquals(original.fingerprint, staged.identity.fingerprint)

        val engine = LspatchPatchEngine(context)
        val result = engine.patch(
            PatchRequest(staged.identity, staged.baseApk, null, "owned target two"),
            workspace,
        )
        assertTrue(result.outputApk.isFile)
        assertEquals(result.sha256, ApkHashing.sha256(result.outputApk))

        // The patched build is a different signing identity: that is exactly why undo exists.
        val patchedSigner = com.android.apksig.ApkVerifier.Builder(result.outputApk).build().verify()
        assertTrue(patchedSigner.isVerified)
        assertNotEquals("re-signing must change the signing identity", original.signerSha256, LspatchPatchEngine.signerSha256())

        assertEquals(listOf(target), listOf(result.sheet.packageName))
        assertFalse("warnings must not be pre-acknowledged", result.sheet.acknowledged)
        assertFalse("an unacknowledged build must not be installable", result.sheet.installable)
        assertTrue("acknowledging makes it installable", result.sheet.acknowledge().installable)
        assertEquals(original.fingerprint, result.sheet.appFingerprint)
        assertEquals(3, result.sheet.warnings.size)

        val archived = OriginalArchive(context).lookup(staged.identity)
        assertNotNull("the original must be archived for undo", archived)
        assertEquals(ApkHashing.sha256(staged.baseApk), ApkHashing.sha256(archived!!.files.first()))

        val record = EvidenceRecord(
            operationId = "patch-test",
            capabilityId = "patch.$target",
            appFingerprint = original.fingerprint,
            packageName = target,
            startedAtMillis = System.currentTimeMillis(),
            durationMs = 1,
            status = EvidenceStatus.SUCCEEDED,
            beforeSha256 = ApkHashing.sha256(staged.baseApk),
            afterSha256 = result.sha256,
            inverse = InverseOp.ReinstallArchived(archived.files.first().absolutePath, ApkHashing.sha256(archived.files.first())),
            note = "device patch test",
        )
        val plan = UndoController(context, OriginalArchive(context)).plan(record, original)
        assertTrue(plan.restoreApk.isFile)
        assertEquals(record.inverse.let { (it as InverseOp.ReinstallArchived).sha256 }, plan.restoreSha256)

        val tampered = runCatching {
            UndoController(context, OriginalArchive(context)).plan(
                record.copy(inverse = InverseOp.ReinstallArchived(archived.files.first().absolutePath, "f".repeat(64))),
                original,
            )
        }
        assertTrue("a changed archive must refuse to restore", tampered.isFailure)
        android.util.Log.i("FR3KPT2", "PATCH_PLANE_OK target=$target sourceSha=${result.sheet.sourceSha256.take(12)} outSha=${result.sha256.take(12)}")
    }

    @Test fun engineRefusesASourceThatDoesNotMatchTheIdentity() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = File(context.noBackupFilesDir, "app-control/workspace").apply { mkdirs() }
        val resolver = TargetResolver(context)
        val staged = resolver.stage(target, workspace)
        val other = resolver.identityOf("com.mcpintelligence.fr3k.patchfixture")
        val refused = runCatching {
            LspatchPatchEngine(context).patch(PatchRequest(other, staged.baseApk, null, "mismatch"), workspace)
        }
        assertTrue("a mismatched identity must be refused", refused.isFailure)
        android.util.Log.i("FR3KPT2", "IDENTITY_GUARD_OK ${refused.exceptionOrNull()?.message}")
    }
}
