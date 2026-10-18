package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import com.android.apksig.ApkVerifier
import com.mcpintelligence.fr3k.capability.AppIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.lsposed.patch.ApkPatcher
import org.lsposed.patch.PatchSpec
import org.lsposed.patch.util.Logger
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Patches a copy of the user's own installed APK on device and re-signs it with a per-install
 * non-exportable AndroidKeyStore key. The original is archived first, so the change is undoable.
 */
class LspatchPatchEngine(private val context: Context) : PatchEngine {

    override val id: String = "lspatch-embedded"

    companion object {
        private val SIGNER_LOCK = Any()
        const val ALIAS = "fr3k.userland.signer.v1"

        fun signingEntry(): KeyStore.PrivateKeyEntry = synchronized(SIGNER_LOCK) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                val generator = java.security.KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
                generator.initialize(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_SIGN or
                            android.security.keystore.KeyProperties.PURPOSE_VERIFY,
                    )
                        .setKeySize(2048)
                        .setDigests(
                            android.security.keystore.KeyProperties.DIGEST_SHA256,
                            android.security.keystore.KeyProperties.DIGEST_SHA512,
                        )
                        .setSignaturePaddings(
                            android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                            android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PSS,
                        )
                        .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=fR3k userland signer"))
                        .setCertificateSerialNumber(java.math.BigInteger.ONE)
                        .build(),
                )
                generator.generateKeyPair()
            }
            store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        }

        fun signerSha256(): String {
            val certificate = signingEntry().certificate
            return java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }

    override suspend fun patch(request: PatchRequest, workspace: File): PatchResult = withContext(Dispatchers.IO) {
        val job = File(workspace, "job-${UUID.randomUUID()}").apply { check(mkdirs()) { "workspace not created" } }
        check(context.noBackupFilesDir.usableSpace > 268_435_456) { "insufficient space: reserve 256 MiB" }
        // Fail closed against the identity's own recorded hashes, not a hash of the file we were handed:
        // otherwise a caller could pair any APK with any identity and the guard would be vacuous.
        PatchContract.requireSourceMatchesIdentity(request.sourceApk, request.identity, request.identity.splitSha256)
        check(ApkVerifier.Builder(request.sourceApk).build().verify().isVerified) { "staged source signature is invalid" }
        val archiveBefore = OriginalArchive(context).store(StagedTarget(request.identity.packageName, request.sourceApk,
            emptyList(), listOf(ApkHashing.sha256(request.sourceApk)), request.identity))

        // The engine calls this from its own threads, so cancellation is checked without suspending.
        val patchJob = currentCoroutineContext()[kotlinx.coroutines.Job]
        val logger = object : Logger() {
            private fun checkpoint() {
                if (patchJob?.isActive == false) throw kotlinx.coroutines.CancellationException("patch cancelled")
            }
            override fun d(msg: String) = checkpoint()
            override fun i(msg: String) = checkpoint()
            override fun e(msg: String) = checkpoint()
        }
        val builder = PatchSpec.builder()
            .apk(request.sourceApk)
            .outputDir(job)
            .keystore(org.lsposed.patch.KeystoreSpec.ofEntry(signingEntry()))
            .useManager(false)
            .sigBypassLevel(0)
        request.moduleApk?.let { builder.module(it) }
        val outputs = ApkPatcher(logger, builder.build()).patch()
        check(outputs.size == 1) { "unexpected patch output count: ${outputs.size}" }
        val result = outputs.single()
        check(result.canonicalFile.parentFile == job.canonicalFile) { "output escaped the job directory" }
        check(ApkVerifier.Builder(result).build().verify().isVerified) { "patched APK signature failed" }
        ZipFile(result).use { zip ->
            val entry = zip.getEntry("assets/lspatch/config.json") ?: error("patched APK has no lspatch config")
            val config = JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
            check(!config.getBoolean("useManager")) { "manager mode must stay off" }
            check(config.getInt("sigBypassLevel") == 0) { "signature bypass must stay off" }
        }
        val sha = ApkHashing.sha256(result)
        val archive = OriginalArchive(context)
        val staged = StagedTarget(
            request.identity.packageName,
            request.sourceApk,
            emptyList(),
            listOf(ApkHashing.sha256(request.sourceApk)),
            request.identity,
        )
        val archived = archiveBefore
        val sheet = PreparationSheet(
            packageName = request.identity.packageName,
            appFingerprint = request.identity.fingerprint,
            engineId = id,
            engineVersion = "lspatch-1.2.1-embedded",
            patchIds = listOfNotNull(request.moduleApk?.name),
            patchBundleDigest = request.moduleApk?.let { ApkHashing.sha256(it) },
            sourceSha256 = ApkHashing.sha256(request.sourceApk),
            outputSha256 = sha,
            archivePath = archived.files.first().absolutePath,
            archiveSha256 = ApkHashing.sha256(archived.files.first()),
            warnings = listOf(
                "Replacing an app with a re-signed build changes its signing identity.",
                "Sign-in services that pin the original signer may stop working for this app.",
                "Uninstalling the old build removes that app's private data.",
            ),
            // Approval is collected by the reviewed installer, never assumed by the engine.
            acknowledged = false,
        )
        // Publish for the reviewed installer only after signature verification succeeded.
        val published = publish(result, request.identity.packageName, sha)
        PatchResult(published, sha, sheet)
    }

    private fun publish(apk: File, packageName: String, sha: String): File {
        val shares = File(context.filesDir, "shares").apply { mkdirs() }
        val dest = File(shares, "$packageName-${sha.take(12)}.apk")
        apk.copyTo(dest, overwrite = true)
        check(ApkHashing.sha256(dest) == sha) { "published copy changed" }
        File(shares, "$packageName-latest.json").writeText(
            JSONObject().put("path", dest.absolutePath).put("sha256", sha).put("packageName", packageName).toString(),
        )
        return dest
    }
}
