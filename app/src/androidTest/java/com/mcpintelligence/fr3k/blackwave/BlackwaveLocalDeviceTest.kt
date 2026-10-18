package com.mcpintelligence.fr3k.blackwave

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveLocalClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlackwaveLocalDeviceTest {
    @Test fun actualHudUidNegotiatesAndReadsBoundService() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = BlackwaveLocalClient(context)
        val name = "com.fr3k.blackwave.debug"
        val preferences = context.getSharedPreferences("blackwave-local-association-v1", android.content.Context.MODE_PRIVATE)
        val prior = preferences.getStringSet(name, null)?.toSet()
        try {
            client.approve(name, client.signers(name))
            val health = client.query(name, JSONObject().put("version", 1).put("method", "health"))
            assertEquals("METADATA_ONLY", health.getString("status"))
            assertEquals("UNINITIALIZED", health.getString("authority"))
            assertFalse(health.getBoolean("hardware_writes_enabled"))
            val devices = client.query(name, JSONObject().put("version", 1).put("method", "devices"))
            assertEquals(0, devices.getJSONArray("devices").length())
            val events = client.query(name, JSONObject().put("version", 1).put("method", "events").put("after", 0))
            assertEquals(0, events.getJSONArray("events").length())
            val guides = client.query(name, JSONObject().put("version", 1).put("method", "guides"))
            val allGuides = guides.getJSONArray("guides")
            assertTrue((0 until allGuides.length()).any { allGuides.getJSONObject(it).getString("id") == "local-association" })
            assertTrue((0 until allGuides.length()).any { allGuides.getJSONObject(it).getString("id") == "enrollment-evidence" })
            val timings = (1..30).map {
                val started = android.os.SystemClock.elapsedRealtimeNanos()
                client.query(name, JSONObject().put("version", 1).put("method", "health"))
                (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            }.sorted()
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("stream", "IPC_WARM_BIND_NEGOTIATE_HEALTH n=30 p95_ms=${timings[28]} max_ms=${timings.last()}\n")
            })
            val denied = client.query(name, JSONObject().put("version", 1).put("method", "firmware.install"))
            assertEquals("METHOD_UNAVAILABLE", denied.getString("error"))
        } finally {
            val editor = preferences.edit()
            if (prior == null) editor.remove(name) else editor.putStringSet(name, prior)
            check(editor.commit())
        }
    }

    @Test fun actualHudUidIsDeniedAfterBlackwaveRevocation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = BlackwaveLocalClient(context)
        val name = "com.fr3k.blackwave.debug"
        val preferences = context.getSharedPreferences("blackwave-local-association-v1", android.content.Context.MODE_PRIVATE)
        val prior = preferences.getStringSet(name, null)?.toSet()
        try {
            client.approve(name, client.signers(name))
            try { client.query(name, JSONObject().put("version", 1).put("method", "health")); fail("Unassociated HUD accepted") }
            catch (_: SecurityException) { }
        } finally {
            val editor = preferences.edit()
            if (prior == null) editor.remove(name) else editor.putStringSet(name, prior)
            check(editor.commit())
        }
    }
}
