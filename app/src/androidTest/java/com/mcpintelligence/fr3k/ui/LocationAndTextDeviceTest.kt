package com.mcpintelligence.fr3k.ui

import android.content.ClipboardManager
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.*
import com.mcpintelligence.fr3k.location.LocationActions
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocationAndTextDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext.applicationContext as Fr3kApplication
    @Test fun gpsCopyUsesAnObservedEmulatorFixAndShareOpensAChooser() = runBlocking {
        val activity = inst.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        var before: android.content.ClipData? = null
        withContext(Dispatchers.Main) { before = clipboard.primaryClip }
        try {
            val result = withContext(Dispatchers.IO) { LocationActions(app).execute("copy") }
            assertTrue("A real emulator GPS fix is required: $result", result is CommandResult.Ok)
            val coordinates = (result as CommandResult.Ok).data.getValue("clipboard")
            withContext(Dispatchers.Main) { assertEquals(coordinates, clipboard.primaryClip!!.getItemAt(0).text.toString()) }
            val share = LocationActions(app).execute("share")
            assertTrue(share.toString(), share is CommandResult.Ok)
            assertTrue(LocationActions(app).execute("waypoint") is CommandResult.Failed)
            File(app.filesDir, "ui-audit/gps.json").apply { parentFile!!.mkdirs() }.writeText(JSONObject()
                .put("source", "Android LocationManager on emulator-5582; host-injected geo fix")
                .put("copy", coordinates).put("share", share.toString()).put("waypoint", "refused: no transport").toString(2))
        } finally { withContext(Dispatchers.Main) { if (before != null) clipboard.setPrimaryClip(before!!) else clipboard.clearPrimaryClip(); activity.finish() } }
    }
    @Test fun systemCommandsReturnActualAndroidValues() {
        val reader = AndroidStatusReader(app)
        val receipt = JSONObject()
        for (kind in listOf("battery", "network", "bluetooth", "storage")) {
            val result = reader.read(kind)
            assertTrue(result.toString(), result is CommandResult.Ok)
            receipt.put(kind, (result as CommandResult.Ok).message)
        }
        File(app.filesDir, "ui-audit/system.json").apply { parentFile!!.mkdirs() }.writeText(receipt.toString(2))
    }
    @Test fun allTextTransformsUseTheConfiguredLiveProvider() = runBlocking {
        val settings = app.settings.settings.value
        assumeTrue("No live provider credential configured", !app.secureStore.get(settings.opencodeApiKeyKey).isNullOrBlank() || !app.secureStore.get(settings.openrouterApiKeyKey).isNullOrBlank())
        app.settings.update { it.copy(consentProfile = ConsentLevel.NORMAL) }
        try {
            val receipt = JSONArray()
            for (id in listOf("share.text.rewrite", "share.text.translate", "share.text.summarise", "share.text.explain")) {
                val command = app.fr3kCore.commandRegistry.get(id) ?: error("Missing $id")
                val result = withTimeout(90000) { command.execute(Fr3kContext("synthetic-ui-audit", enabledCapabilities = command.requiredCapabilities), mapOf("text" to "FR3K verification sentence. The sky is blue.")) }
                assertTrue("$id: $result", result is CommandResult.Ok)
                assertTrue((result as CommandResult.Ok).message.isNotBlank())
                assertFalse(result.message.contains("queued (placeholder)"))
                receipt.put(JSONObject().put("command", id).put("provider", app.activeProviderLabel).put("receivedText", true))
            }
            File(app.filesDir, "ui-audit/live-text.json").apply { parentFile!!.mkdirs() }.writeText(receipt.toString(2))
        } finally { app.settings.update { settings } }
    }
}
