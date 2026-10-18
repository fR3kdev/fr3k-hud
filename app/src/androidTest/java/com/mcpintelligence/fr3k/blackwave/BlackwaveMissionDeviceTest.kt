package com.mcpintelligence.fr3k.blackwave

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveLocalClient
import com.mcpintelligence.fr3k.integrations.blackwave.LocalReceiptBytes
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class BlackwaveMissionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageName = "com.fr3k.blackwave.debug"
    private val saved get() = context.getSharedPreferences("cross-app-mission-test", Context.MODE_PRIVATE)
    private suspend fun query(method: String, request: JSONObject = JSONObject()): JSONObject {
        val client = BlackwaveLocalClient(context)
        return client.query(packageName, request.put("version", 1).put("method", method))
    }
    @Test fun previewSameRequestAgainstTwoActualEnrolledIdentities() = runBlocking {
        val client = BlackwaveLocalClient(context)
        val pins = context.getSharedPreferences("blackwave-local-association-v1", Context.MODE_PRIVATE)
        val prior = pins.getStringSet(packageName, null)?.toSet()
        check(saved.edit().putBoolean("prior_present", prior != null).putStringSet("prior_pin", prior ?: emptySet()).commit())
        client.approve(packageName, client.signers(packageName))
        val units = query("devices").getJSONArray("devices")
        assertEquals(2, units.length())
        assertNotEquals(units.getJSONObject(0).getString("device_id"), units.getJSONObject(1).getString("device_id"))
        assertNotEquals(units.getJSONObject(0).getString("identity_ref"), units.getJSONObject(1).getString("identity_ref"))
        val target = units.getJSONObject(0).getString("identity_ref")
        val manifest = query("capabilities", JSONObject().put("target_id", target))
        assertEquals("evidence.enrollment", manifest.getJSONArray("local_actions").getJSONObject(0).getString("id"))
        val request = UUID.randomUUID().toString()
        val args = JSONObject().put("request_id", request).put("target_id", target).put("action", "evidence.enrollment")
        val preview = query("preview", args).getJSONObject("job")
        assertEquals(preview.getString("operation_id"), query("preview", args).getJSONObject("job").getString("operation_id"))
        assertEquals("PREVIEW", preview.getString("state"))
        assertEquals("APPROVAL_REQUIRED", query("submit", JSONObject().put("operation_id", preview.getString("operation_id"))).getString("error"))
        check(saved.edit().putString("operation_id", preview.getString("operation_id")).putString("request_id", request).putString("target_id", target).commit())
    }
    @Test fun reconnectRetainsApprovedJobAndVerifiesSignedBoundedArtifact() = runBlocking {
        val operation = checkNotNull(saved.getString("operation_id", null))
        val job = query("operation", JSONObject().put("operation_id", operation)).getJSONObject("job")
        assertEquals("SUCCEEDED", job.getString("state"))
        assertEquals(saved.getString("request_id", null), job.getString("request_id"))
        assertEquals(saved.getString("target_id", null), job.getString("target_id"))
        assertEquals(operation, query("operation", JSONObject().put("request_id", job.getString("request_id"))).getJSONObject("job").getString("operation_id"))
        val receipt = job.getJSONObject("receipt")
        val payload = receipt.getJSONObject("payload")
        val public = Base64.getDecoder().decode(query("health").getString("controller_public_key"))
        val bytes = LocalReceiptBytes.canonical(payload)
        val verifier = org.bouncycastle.crypto.signers.Ed25519Signer().apply { init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(public, 0)); update(bytes, 0, bytes.size) }
        assertTrue(verifier.verifySignature(Base64.getDecoder().decode(receipt.getString("signature"))))
        val input = context.contentResolver.openInputStream(Uri.parse(payload.getString("artifact_uri")))!!
        val data = input.use { it.readBytes() }
        assertTrue(data.size <= 256 * 1024)
        assertEquals(payload.getInt("artifact_bytes"), data.size)
        assertEquals(payload.getString("artifact_sha256"), MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
        assertEquals(operation, JSONObject(String(data)).getString("operation_id"))
        assertFalse(JSONObject(String(data)).getBoolean("device_live"))
    }
    @Test fun restoreClientAssociation() {
        if (!saved.contains("prior_present")) return
        val pins = context.getSharedPreferences("blackwave-local-association-v1", Context.MODE_PRIVATE)
        val editor = pins.edit()
        if (saved.getBoolean("prior_present", false)) editor.putStringSet(packageName, saved.getStringSet("prior_pin", emptySet())!!.toSet()) else editor.remove(packageName)
        check(editor.commit()); check(saved.edit().clear().commit())
    }
}
