package com.mcpintelligence.fr3k.integrations.openrouter

import com.mcpintelligence.fr3k.core.appcontrol.AppControlPlanTool
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppControlToolSchemaTest {
    @Test fun packageIsRequiredAndNoCallerPrivilegeClaimsAreAllowed() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""))
            val provider = OpenRouterProvider(endpoint = server.url("/api/v1").toString(), apiKeyProvider = { "fixture-key" })
            provider.complete(JSONArray().put(JSONObject().put("role", "user").put("content", "inspect")), listOf(AppControlPlanTool()))
            val body = JSONObject(server.takeRequest().body.readUtf8())
            val schema = body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters")
            assertTrue(schema.getJSONObject("properties").has("package"))
            assertEquals("package", schema.getJSONArray("required").getString(0))
            assertFalse(schema.getJSONObject("properties").has("runtimeLoaded"))
            assertFalse(schema.getBoolean("additionalProperties"))
        } finally { server.shutdown() }
    }
}
