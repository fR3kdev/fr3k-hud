package com.mcpintelligence.fr3k.blackwave

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveLocalClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Run with the actual older HUD APK, whose client does not declare native evidence support. */
class NativeLegacyClientDeviceTest {
    @Test fun olderHudCannotPreviewOrMislabelNativeEvidence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "com.fr3k.blackwave.debug"
        val pins = context.getSharedPreferences("blackwave-local-association-v1", Context.MODE_PRIVATE)
        val before = pins.getStringSet(name, null)?.toSet()
        val client = BlackwaveLocalClient(context)
        try {
            client.approve(name, client.signers(name))
            suspend fun query(method: String, p: JSONObject = JSONObject()) = client.query(name, p.put("version", 1).put("method", method))
            val target = checkNotNull(InstrumentationRegistry.getArguments().getString("target_id"))
            val actions = query("capabilities", JSONObject().put("target_id", target)).getJSONArray("local_actions")
            assertEquals(1, actions.length()); assertEquals("evidence.enrollment", actions.getJSONObject(0).getString("id"))
            try {
                query("preview", JSONObject().put("request_id", java.util.UUID.randomUUID().toString()).put("target_id", target).put("action", "device.status"))
                fail("Older HUD native preview accepted")
            } catch (e: Exception) { assertTrue(e.message?.contains("native-missions/1") == true) }
            val jobs = query("missions").getJSONArray("jobs")
            for (i in 0 until jobs.length()) {
                val job = jobs.getJSONObject(i)
                if (job.getString("action").startsWith("device.")) {
                    assertEquals("CLIENT_UPDATE_REQUIRED", job.getString("state")); assertTrue(job.isNull("receipt"))
                    assertEquals("SUCCEEDED", job.getString("device_operation_state"))
                }
            }
        } finally {
            val editor = pins.edit(); if (before == null) editor.remove(name) else editor.putStringSet(name, before)
            check(editor.commit())
        }
    }
}
