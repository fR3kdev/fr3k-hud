package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import com.android.apksig.ApkVerifier
import kotlinx.coroutines.*
import org.json.JSONObject
import org.lsposed.patch.ApkPatcher
import org.lsposed.patch.PatchSpec
import org.lsposed.patch.util.Logger
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Private lab qualification only. No arbitrary targets, install, root, signature bypass or private data. */
class EmbeddedFixturePatchEngine(private val context: Context) {
    companion object { private val SIGNER_LOCK = Any() }
    data class Prepared(val apk: File, val sha256: String)
    suspend fun prepare(): Prepared = withContext(Dispatchers.IO) {
        val task = currentCoroutineContext()[Job]
        val root = File(context.noBackupFilesDir, "app-control/jobs/${UUID.randomUUID()}")
        check(context.noBackupFilesDir.usableSpace > 268_435_456) { "Insufficient space: reserve 256 MiB" }
        check(root.mkdirs()) { "Could not create private job directory" }
        fun stage(name: String, expected: String): File {
            val dest = File(root, name)
            context.assets.open("app-control/$name").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            check(digest(dest) == expected) { "Bundled fixture payload changed" }
            return dest
        }
        val source = stage("owned-fixture.apk", "7f6f84a470fbc4dd7c7e828c0bc2845e775a4cc803b5da8a85ffc36731f28db7")
        val module = stage("owned-fixture-hook.apk", "65842fadc40a147ddb12aa608468e72fad5093b52958a8d5ef85050d4326134d")
        check(ApkVerifier.Builder(source).build().verify().isVerified) { "Invalid owned source signature" }
        val info = context.packageManager.getPackageArchiveInfo(source.path, 0)
        check(info?.packageName == "com.mcpintelligence.fr3k.patchfixture" && info.longVersionCode == 1L)
        val logger = object : Logger() {
            override fun d(msg: String) { task?.ensureActive() }
            override fun i(msg: String) { task?.ensureActive() }
            override fun e(msg: String) { task?.ensureActive() }
        }
        // A per-install non-exportable AndroidKeyStore entry; no shared demo signing key.
        val spec = PatchSpec.builder().apk(source).module(module).outputDir(root)
            .keystore(org.lsposed.patch.KeystoreSpec.ofEntry(signingEntry()))
            .useManager(false).sigBypassLevel(0).build()
        val outputs = ApkPatcher(logger, spec).patch()
        check(outputs.size == 1) { "Unexpected patch output count" }
        val result = outputs.single()
        check(result.canonicalFile.parentFile == root.canonicalFile)
        check(ApkVerifier.Builder(result).build().verify().isVerified) { "Prepared APK signature failed" }
        ZipFile(result).use { zip ->
            val config = JSONObject(zip.getInputStream(zip.getEntry("assets/lspatch/config.json"))
                .bufferedReader().use { it.readText() })
            check(!config.getBoolean("useManager") && config.getInt("sigBypassLevel") == 0)
        }
        task?.ensureActive()
        val shares = File(context.filesDir, "shares").apply { mkdirs() }
        val published = File(shares, "owned-fixture-${UUID.randomUUID()}.apk")
        result.copyTo(published)
        val sha = digest(published)
        check(sha == digest(result))
        context.getSharedPreferences("app-control.lab", Context.MODE_PRIVATE).edit()
            .putString("prepared.sha256", sha).commit().also { check(it) }
        File(shares, "owned-fixture-latest.json").writeText(JSONObject()
            .put("path", published.absolutePath).put("sha256", sha).toString())
        Prepared(published, sha)
    }
    private fun signingEntry(): java.security.KeyStore.PrivateKeyEntry = synchronized(SIGNER_LOCK) {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "fr3k.owned-fixture.signer.v1"
        if (!store.containsAlias(alias)) {
            val generator = java.security.KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
            generator.initialize(android.security.keystore.KeyGenParameterSpec.Builder(alias,
                android.security.keystore.KeyProperties.PURPOSE_SIGN or android.security.keystore.KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256,
                    android.security.keystore.KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                    android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=fR3k Owned Fixture Lab"))
                .setCertificateSerialNumber(java.math.BigInteger.ONE)
                .build())
            generator.generateKeyPair()
        }
        store.getEntry(alias, null) as java.security.KeyStore.PrivateKeyEntry
    }
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
