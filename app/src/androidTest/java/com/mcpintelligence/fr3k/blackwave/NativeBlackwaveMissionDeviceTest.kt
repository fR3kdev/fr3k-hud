package com.mcpintelligence.fr3k.blackwave

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.integrations.blackwave.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Actual authenticated Binder + phone UI approval + real compiled native code via explicit test bearer. */
class NativeBlackwaveMissionDeviceTest {
    @Test fun nativeMissionRoundTrip() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val packageName = "com.fr3k.blackwave.debug"
        val saved = context.getSharedPreferences("native-cross-app-mission-fixture-v1", Context.MODE_PRIVATE)
        val client = BlackwaveLocalClient(context)
        suspend fun query(method: String, parameters: JSONObject = JSONObject()) = client.query(packageName, parameters.put("version", 1).put("method", method))
        when (args.getString("fixtureMode")) {
            "preview" -> {
                val pins = context.getSharedPreferences("blackwave-local-association-v1", Context.MODE_PRIVATE)
                val prior = pins.getStringSet(packageName, null)?.toSet()
                check(saved.edit().putBoolean("prior_present", prior != null).putStringSet("prior_pin", prior ?: emptySet()).commit())
                client.approve(packageName, client.signers(packageName))
                val target = checkNotNull(args.getString("target_id"))
                val units = query("devices").getJSONArray("devices")
                assertEquals(2, units.length())
                assertEquals(units.getJSONObject(0).getString("model_id"), units.getJSONObject(1).getString("model_id"))
                assertNotEquals(units.getJSONObject(0).getString("device_id"), units.getJSONObject(1).getString("device_id"))
                assertNotEquals(units.getJSONObject(0).getString("identity_ref"), units.getJSONObject(1).getString("identity_ref"))
                val available = query("capabilities", JSONObject().put("target_id", target)).getJSONArray("local_actions")
                assertTrue((0 until available.length()).any { available.getJSONObject(it).getString("id") == (args.getString("native_action") ?: "device.set_volume") })
                val action = args.getString("native_action") ?: "device.set_volume"
                val request = UUID.randomUUID().toString()
                val parameters = JSONObject().put("request_id", request).put("target_id", target).put("action", action)
                    .put("parameters", if (action == "device.set_volume") BlackwaveLocalParameters.parse("{\"volume\":0}") else JSONObject())
                val job = query("preview", parameters).getJSONObject("job")
                assertEquals("PREVIEW", job.getString("state"))
                assertEquals(job.getString("operation_id"), query("preview", parameters).getJSONObject("job").getString("operation_id"))
                assertEquals("APPROVAL_REQUIRED", query("submit", JSONObject().put("operation_id", job.getString("operation_id"))).getString("error"))
                val cursor = query("events").getLong("cursor")
                check(saved.edit().putString("operation_id", job.getString("operation_id")).putString("request_id", request).putString("target_id", target).putLong("cursor", cursor).commit())
                output("NATIVE_OPERATION", job.getString("operation_id"))
            }
            "verify", "verify-cancelled" -> {
                val operation = checkNotNull(saved.getString("operation_id", null))
                val job = query("operation", JSONObject().put("operation_id", operation)).getJSONObject("job")
                val expectedState = if (args.getString("fixtureMode") == "verify-cancelled") "FAILED" else "SUCCEEDED"
                assertEquals(expectedState, job.getString("state"))
                assertEquals(saved.getString("request_id", null), job.getString("request_id")); assertEquals(saved.getString("target_id", null), job.getString("target_id"))
                assertEquals(operation, query("operation", JSONObject().put("request_id", job.getString("request_id"))).getJSONObject("job").getString("operation_id"))
                val receipt = job.getJSONObject("receipt"); val p = receipt.getJSONObject("payload")
                val public = Base64.getDecoder().decode(query("health").getString("controller_public_key"))
                val bytes = LocalReceiptBytes.canonical(p)
                val verifier = org.bouncycastle.crypto.signers.Ed25519Signer().apply { init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(public, 0)); update(bytes, 0, bytes.size) }
                assertTrue(verifier.verifySignature(Base64.getDecoder().decode(receipt.getString("signature"))))
                assertEquals(job.getString("operation_digest"), p.getString("operation_digest"))
                val uri = Uri.parse(p.getString("artifact_uri")); assertEquals("$packageName.mission-evidence", uri.authority)
                val data = context.contentResolver.openInputStream(uri)!!.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val block = ByteArray(8192)
                    while (true) { val count = input.read(block); if (count < 0) break; require(out.size() + count <= 256 * 1024); out.write(block, 0, count) }; out.toByteArray()
                }
                assertEquals(p.getInt("artifact_bytes"), data.size)
                assertEquals(p.getString("artifact_sha256"), MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
                val artifact = NativeDeviceEvidence.parse(data)
                val verified = NativeDeviceEvidence.verify(artifact, job, public)
                assertTrue(verified.contains(if (expectedState == "FAILED") "not applied" else "SUCCEEDED"))
                if (expectedState == "SUCCEEDED") assertEquals(0, artifact.getJSONObject("device_receipt").getJSONObject("payload").getJSONObject("result").getInt("volume"))
                val page = withTimeout(15000) { client.events(packageName, saved.getLong("cursor", 0)).first() }
                val events = page.getJSONArray("events")
                assertTrue((0 until events.length()).any { events.getJSONObject(it).getString("operation_id") == operation && events.getJSONObject(it).getString("state") == expectedState })
                output("NATIVE_VERIFIED_OPERATION", operation)
            }
            "restore" -> {
                if (saved.contains("prior_present")) {
                    val pins = context.getSharedPreferences("blackwave-local-association-v1", Context.MODE_PRIVATE).edit()
                    if (saved.getBoolean("prior_present", false)) pins.putStringSet(packageName, saved.getStringSet("prior_pin", emptySet())!!.toSet()) else pins.remove(packageName)
                    check(pins.commit()); check(saved.edit().clear().commit())
                }
            }
            else -> error("Explicit fixtureMode preview/verify/restore required")
        }
    }
    private fun output(name: String, value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "$name=$value\n") })
    }
}
