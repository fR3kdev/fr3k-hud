package com.mcpintelligence.fr3k.appcontrol

import android.app.Activity
import android.content.*
import android.os.*
import com.mcpintelligence.fr3k.core.tools.*
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only the exact owned fixture APK prepared on this HUD installation can be controlled. */
class OwnedFixtureController(private val context: Context) {
    companion object { const val TARGET = "com.mcpintelligence.fr3k.patchfixture" }
    suspend fun change(visible: Boolean): String = withContext(Dispatchers.IO) {
        val expected = context.getSharedPreferences("app-control.lab", Context.MODE_PRIVATE)
            .getString("prepared.sha256", null) ?: error("Prepare and install the owned fixture first")
        val before = AndroidPackageInspector(context).inspect(TARGET)
        check(before.splitSha256 == listOf(expected)) { "Installed fixture is not this HUD's prepared APK" }
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val certificate = store.getCertificate("fr3k.owned-fixture.signer.v1") ?: error("Fixture signing identity unavailable")
        val signer = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(before.signerSha256 == signer) { "Fixture signer mismatch" }
        val id = UUID.randomUUID().toString()
        val receipt = withTimeout(5000) {
            suspendCancellableCoroutine<Bundle> { continuation ->
                val intent = Intent("com.mcpintelligence.fr3k.hud.SET_OWNED_FIXTURE_BANNER")
                    .setPackage(TARGET).putExtra("visible", visible).putExtra("operationId", id)
                context.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (!continuation.isActive) return
                        val extras = getResultExtras(false)
                        if (resultCode == Activity.RESULT_OK && resultData == "OWNED_METHOD_OBSERVED" &&
                            extras?.getString("operationId") == id && extras.getBoolean("observed") == visible &&
                            extras.getInt("pid") > 0) continuation.resume(extras)
                        else continuation.resumeWithException(IllegalStateException("Owned fixture runtime is not ready; launch the prepared fixture first"))
                    }
                }, Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null)
            }
        }
        check(AndroidPackageInspector(context).inspect(TARGET) == before) { "Target changed during operation" }
        "Owned fixture method observed: bannerVisible=$visible pid=${receipt.getInt("pid")} operation=$id. No other app modified."
    }
}

/** Existing SharedAgentSession requires visible user approval for this capability. */
class OwnedFixtureAgentTool(private val controller: OwnedFixtureController) : AgentTool {
    override val id = "app.control.fixture.banner"
    override val displayName = "Change OWNED TEST FIXTURE banner"
    override val description = "ONLY changes the prepared com.mcpintelligence.fr3k.patchfixture local test banner; not YouTube or any third-party app. Requires visible approval. visible is true or false."
    override val capability = "app.control.fixture.write"
    override val requiresNetwork = false
    override suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult {
        val raw = args.require("visible")
        require(raw == "true" || raw == "false") { "visible must be true or false" }
        return ToolResult.Success(controller.change(raw == "true"))
    }
}
