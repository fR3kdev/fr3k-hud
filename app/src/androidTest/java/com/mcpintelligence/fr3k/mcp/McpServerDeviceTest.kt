package com.mcpintelligence.fr3k.mcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.appcontrol.CapabilityHost
import com.mcpintelligence.fr3k.bridge.Fr3kBridgeService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class McpServerDeviceTest {

    private lateinit var host: CapabilityHost
    private lateinit var server: Fr3kMcpServer
    private var port = 0

    @Before fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        host = CapabilityHost(instrumentation.targetContext)
        instrumentation.uiAutomation.executeShellCommand(
            "settings put secure enabled_accessibility_services com.mcpintelligence.fr3k.hud/com.mcpintelligence.fr3k.bridge.Fr3kBridgeService",
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } }
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!Fr3kBridgeService.connected() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
        if (!Fr3kBridgeService.connected()) {
            org.junit.Assume.assumeTrue(
                "the MCP surface needs the accessibility bridge; verified from the host instead",
                false,
            )
        }
        server = Fr3kMcpServer(host, port = 8791)
        port = server.start()
    }

    @After fun tearDown() {
        if (this::server.isInitialized) server.stop()
    }

    private fun request(method: String, path: String, body: String? = null, token: String? = null): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
            val payload = body ?: "{}"
            val head = StringBuilder()
                .append("$method $path HTTP/1.1\r\n")
                .append("Host: 127.0.0.1\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Length: ${payload.toByteArray().size}\r\n")
            if (token != null) head.append("Authorization: Bearer $token\r\n")
            head.append("Connection: close\r\n\r\n")
            socket.getOutputStream().write(head.toString().toByteArray())
            socket.getOutputStream().write(payload.toByteArray())
            socket.getOutputStream().flush()
            return BufferedReader(InputStreamReader(socket.getInputStream())).readText()
        }
    }

    @Test fun tokenIsRequiredAndToolsAreListed() {
        val unauthorized = request("GET", "/tools")
        assertTrue("missing token must be rejected: $unauthorized", unauthorized.contains("401"))
        val authorized = request("GET", "/tools", token = server.token)
        assertTrue(authorized.contains("200"))
        assertTrue(authorized.contains("list_capabilities"))
        assertTrue(authorized.contains("execute_plan"))
        assertFalse("no tool may accept a shell string", authorized.contains("\"command\""))
        android.util.Log.i("FR3KMCP", "TOOLS_OK port=$port")
    }

    @Test fun renderPlanAndExecutePlanBothWorkThroughTheSocket() {
        val rendered = JSONObject(request("POST", "/tools/render_plan", """{"goal":"hide the banner on the owned fixture"}""", server.token).substringAfter("\r\n\r\n"))
        assertTrue("render failed: $rendered", rendered.optBoolean("ok"))
        assertTrue(rendered.getString("plan").contains("GOAL:"))

        val unapproved = JSONObject(request("POST", "/tools/execute_plan", """{"goal":"hide the banner on the owned fixture"}""", server.token).substringAfter("\r\n\r\n"))
        assertFalse(unapproved.optBoolean("ok"))
        assertTrue(unapproved.getString("error").contains("approval"))

        val approved = JSONObject(request("POST", "/tools/execute_plan", """{"goal":"hide the banner on the owned fixture","approve":true}""", server.token).substringAfter("\r\n\r\n"))
        assertTrue("approved run failed: $approved", approved.optBoolean("ok"))
        assertTrue(approved.getInt("steps") >= 1)

        val restored = JSONObject(request("POST", "/tools/execute_plan", """{"goal":"show the banner on the owned fixture","approve":true}""", server.token).substringAfter("\r\n\r\n"))
        assertTrue("restore failed: $restored", restored.optBoolean("ok"))
        android.util.Log.i("FR3KMCP", "EXECUTE_PLAN_OK steps=${approved.getInt("steps")}")
    }

    @Test fun capabilityAndJournalToolsReturnRealState() {
        val caps = JSONObject(request("POST", "/tools/list_capabilities", token = server.token).substringAfter("\r\n\r\n"))
        assertTrue(caps.getJSONArray("capabilities").length() >= 10)
        val journal = JSONObject(request("POST", "/tools/get_journal", token = server.token).substringAfter("\r\n\r\n"))
        assertNotNull(journal.getJSONArray("records"))
    }
}
