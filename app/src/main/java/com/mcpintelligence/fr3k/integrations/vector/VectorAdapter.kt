package com.mcpintelligence.fr3k.integrations.vector

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.TimeUnit

/** Manager discovery is informational; root is verified only by an explicit probe. */
class VectorAdapter(private val context: Context) {
    data class Status(
        val rootAvailable: Boolean,
        val suPath: String?,
        val vectorPackage: String?,
        val lspatchPackages: List<String>,
        val activeTier: Tier,
    )
    enum class Tier { TIER_0_NORMAL, TIER_1_USER_GRANTED, TIER_2_SHIZUKU, TIER_3_LSPATCH, TIER_4_ROOT }
    private val knownVectorPkgs = listOf("org.lsposed.vector", "com.jingmatrix.lsposed",
        "io.github.jingmatrix.lsposed", "org.lsposed.lspd", "de.robv.android.xposed.installer")
    private val knownLspatchPkgs = listOf("org.lsposed.lspatch", "io.github.jingmatrix.lspatch", "com.itsaky.lspatch")
    @Volatile private var rootVerifiedAt = -1L

    /** No process creation or permission prompts during status rendering. */
    fun status(): Status {
        val root = hasRoot()
        return Status(root, if (root) "su" else null,
            knownVectorPkgs.firstOrNull { installed(it) }, knownLspatchPkgs.filter { installed(it) },
            if (root) Tier.TIER_4_ROOT else Tier.TIER_0_NORMAL)
    }
    /** Cached recent observation, not a request for new privileges. */
    fun probeRoot(): String? = if (hasRoot()) "su" else null
    fun hasRoot(): Boolean {
        val observed = rootVerifiedAt
        return observed >= 0 && SystemClock.elapsedRealtime() - observed in 0..30_000
    }
    fun hasVector(): Boolean = knownVectorPkgs.any { installed(it) }
    fun hasLspatch(): Boolean = knownLspatchPkgs.any { installed(it) }
    private fun installed(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Legacy UI calls this from an explicit smoke-test tap on Dispatchers.IO.
     * Only the fixed identity probe is supported; this is not a hook executor.
     */
    fun runRootedShell(command: String, timeoutMs: Long = 8000): String {
        if (command != "id") return "unsupported: only explicit root identity probe is available"
        if (Looper.myLooper() == Looper.getMainLooper()) return "error: root probe must run off the UI thread"
        rootVerifiedAt = -1
        var process: Process? = null
        return try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            process = p
            // id output is tiny. Keep draining bounded so unexpected output cannot deadlock waitFor.
            val output = java.io.ByteArrayOutputStream()
            val reader = Thread {
                runCatching {
                    p.inputStream.use { input ->
                        val buffer = ByteArray(1024)
                        var n = input.read(buffer)
                        while (n >= 0) {
                            synchronized(output) {
                                if (output.size() < 8192) output.write(buffer, 0, minOf(n, 8192 - output.size()))
                            }
                            n = input.read(buffer)
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            if (!p.waitFor(timeoutMs.coerceIn(1, 8000), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                "error: root identity probe timed out"
            } else {
                reader.join(500)
                val text = synchronized(output) { output.toString("UTF-8") }
                if (!reader.isAlive && RootPrivilegeEvidence.isVerified(p.exitValue(), text)) {
                    rootVerifiedAt = SystemClock.elapsedRealtime()
                    text
                } else "error: root UID not verified (exit=${p.exitValue()})"
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            "error: root identity probe interrupted"
        } catch (e: Exception) {
            "error: root identity probe failed: ${e.javaClass.simpleName}"
        } finally {
            process?.let { p ->
                if (p.isAlive) p.destroyForcibly()
                runCatching { p.inputStream.close() }
                runCatching { p.outputStream.close() }
                runCatching { p.errorStream.close() }
            }
        }
    }
}
