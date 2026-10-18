package com.mcpintelligence.fr3k.appcontrol

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.capability.appmod.*
import com.mcpintelligence.fr3k.mcp.Fr3kMcpServer
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class AppModMcpDeviceTest {
    @Test fun actualSocketRetainsFiveClosedToolsAndCannotApprovePreparation() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Fr3kApplication
        val server = Fr3kMcpServer(CapabilityHost(app), 0)
        val port = server.start()
        fun request(path: String, body: JSONObject? = null, authorized: Boolean = true): Pair<String, JSONObject> = Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 60000
            val payload = body?.toString()?.toByteArray() ?: byteArrayOf()
            val auth = if (authorized) "Authorization: Bearer ${server.token}\r\n" else ""
            val header = "${if (body == null) "GET" else "POST"} $path HTTP/1.1\r\nHost: localhost\r\n${auth}Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(header.toByteArray()); socket.getOutputStream().write(payload); socket.getOutputStream().flush()
            val response = socket.getInputStream().bufferedReader().readText()
            response.substringBefore("\r\n") to JSONObject(response.substringAfter("\r\n\r\n"))
        }
        try {
            assertTrue(request("/tools", authorized = false).first.contains("401"))
            val tools = request("/tools").second.getJSONArray("tools")
            val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet()
            assertTrue(names.containsAll(AppModCatalogue.tools.keys))
            assertTrue(names.containsAll(setOf("list_capabilities", "get_guides", "get_journal", "get_app_identity")))
            for (i in 0 until tools.length()) {
                val tool = tools.getJSONObject(i)
                if (tool.getString("name") in AppModCatalogue.tools) {
                    val fields = tool.getJSONObject("parameters").keys().asSequence().toSet()
                    assertFalse(fields.any { it.contains("token", true) || it in setOf("approve", "command", "script", "path") })
                }
            }
            val source = app.appModWorkflow.artifacts.capture(listOf({ app.assets.open("app-control/appmod-gallery-fixture.apk") }), SourceKind.IMPORTED_APK, "Owned MCP fixture")
            val analysis = request("/tools/analyze_apk", JSONObject().put("artifact_id", source.id)).second.getString("analysisId")
            val suggestions = request("/tools/suggest_app_mods", JSONObject().put("analysis_id", analysis)).second.getJSONArray("suggestions")
            val target = (0 until suggestions.length()).map { ModificationSuggestion.fromJson(suggestions.getJSONObject(it)) }.single { it.title.contains("gallery_caption") }
            val preview = request("/tools/preview_app_mods", JSONObject().put("analysis_id", analysis).put("selections", JSONArray().put(JSONObject().put("suggestionId", target.suggestionId).put("replacement", "Socket review")))).second
            val id = preview.getString("previewId")
            assertEquals(id, request("/tools/get_app_mod_job", JSONObject().put("job_id", id)).second.getString("jobId"))
            val forged = request("/tools/request_app_mod_preparation", JSONObject().put("preview_id", id).put("approve", true)).second
            assertFalse(forged.optBoolean("ok", true))
            app.startActivity(Intent(app, com.mcpintelligence.fr3k.ui.appcontrol.AppModDiscoveryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertEquals("AWAITING_USER_REVIEW", request("/tools/request_app_mod_preparation", JSONObject().put("preview_id", id)).second.getString("status"))
            assertNull(app.modificationStore.record("outputs", id))
            assertEquals(ModificationStage.PREVIEWING, app.modificationStore.getJob(id)!!.stage)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "APPMOD_MCP_SOCKET_PASS fiveTools=5 approvalRefused=true\n") })
        } finally { server.stop() }
    }
}
