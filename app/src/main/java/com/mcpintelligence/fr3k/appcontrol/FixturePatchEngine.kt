package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Binds the bundled owned-fixture payloads to the generic engine. Kept separate so the qualification
 * path can never accidentally target anything other than the owned test fixtures.
 */
class FixturePatchEngine(private val context: Context) : PatchEngine {
    override val id = "fixture-lab"

    private val engine by lazy { LspatchPatchEngine(context) }

    override suspend fun patch(request: PatchRequest, workspace: File): PatchResult {
        val allowed = listOf(
            "com.mcpintelligence.fr3k.patchfixture",
            "com.mcpintelligence.fr3k.patchfixturetwo",
        )
        require(request.identity.packageName in allowed) { "fixture engine refuses non-owned target" }
        return engine.patch(request, workspace)
    }

    suspend fun stageBundled(name: String, expectedSha: String): File = withContext(Dispatchers.IO) {
        val root = File(context.noBackupFilesDir, "app-control/bundled").apply { mkdirs() }
        val dest = File(root, name)
        currentCoroutineContext().ensureActive()
        if (!dest.isFile || ApkHashing.sha256(dest) != expectedSha) {
            context.assets.open("app-control/$name").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        check(ApkHashing.sha256(dest) == expectedSha) { "bundled payload '$name' changed" }
        dest
    }
}
