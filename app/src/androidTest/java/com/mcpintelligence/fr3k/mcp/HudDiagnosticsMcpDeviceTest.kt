package com.mcpintelligence.fr3k.mcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.appcontrol.CapabilityHost
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.net.InetSocketAddress

@RunWith(AndroidJUnit4::class)
class HudDiagnosticsMcpDeviceTest {
    private lateinit var server: Fr3kMcpServer
    @Before fun setup() {
        server = Fr3kMcpServer(CapabilityHost(InstrumentationRegistry.getInstrumentation().targetContext), port = 18793)
        server.start()
    }
    @After fun close() { server.stop() }
    private fun request(path: String, authorized: Boolean = true, args: String = "{}"): Pair<Int, JSONObject> {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 18793), 3000)
            socket.soTimeout = 6000
            val headers = StringBuilder()
                .append("POST " + path + " HTTP/1.1\r\nHost: localhost\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Length: " + args.toByteArray().size + "\r\n")
            if (authorized) headers.append("Authorization: Bearer " + server.token + "\r\n")
            headers.append("Connection: close\r\n\r\n")
            socket.getOutputStream().write(headers.toString().toByteArray())
            socket.getOutputStream().write(args.toByteArray())
            socket.getOutputStream().flush()
            val raw = socket.getInputStream().bufferedReader().readText()
            return raw.substringAfter("HTTP/1.1 ").take(3).toInt() to
                JSONObject(raw.substringAfter("\r\n\r\n"))
        }
    }
    @Test fun screenInventoryAndLocalControlGateAreReal() {
        assertEquals(401, request("/tools/get_hud_diagnostics", authorized = false).first)
        val screens = request("/tools/list_hud_screens").second
        assertTrue(screens.toString(), screens.getInt("count") >= 18)
        val ids = screens.getJSONArray("screens")
        assertTrue((0 until ids.length()).any { ids.getJSONObject(it).getString("id") == "onboarding" })
        assertTrue((0 until ids.length()).any { ids.getJSONObject(it).getString("id") == "appmod" })
        val status = request("/tools/get_hud_diagnostics").second
        assertTrue(status.getBoolean("mcpRunning"))
        assertFalse(status.getBoolean("controlAuthorized"))
        assertFalse(request("/tools/open_hud_screen",args = """{"id":"settings"}""").second.getBoolean("ok"))
        assertFalse(request("/tools/open_hud_screen",args = """{"id":"com.android.settings/.Settings"}""").second.getBoolean("ok"))
        val selftest = request("/tools/run_hud_selftest").second
        assertEquals(4, selftest.getInt("total"))
        assertTrue(selftest.getInt("passed") >= 3)
        assertFalse(request("/tools/press_hud_control",args = """{"path":"0.0"}""").second.getBoolean("ok"))
        server.diagnostics.allowControlsTenMinutes()
        assertTrue(request("/tools/get_hud_diagnostics").second.getBoolean("controlAuthorized"))
        assertTrue(request("/tools/open_hud_screen",args = """{"id":"settings"}""").second.getBoolean("ok"))
        assertFalse(request("/tools/open_hud_screen",args = """{"id":"nonexistent"}""").second.getBoolean("ok"))
        server.stop()
        assertFalse(server.diagnostics.controlsEnabled())
    }
}
