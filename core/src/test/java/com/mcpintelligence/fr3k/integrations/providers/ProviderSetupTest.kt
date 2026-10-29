package com.mcpintelligence.fr3k.integrations.providers

import com.mcpintelligence.fr3k.core.AppSettings
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test

class ProviderSetupTest {
    @Test fun `exhausted OpenRouter credit verifies free router before saving fallback`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(402))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        server.start()
        try {
            val settings = AppSettings()
            val secrets = mutableMapOf<String, String>()
            val service = ProviderSetupService(settings, { k, v -> secrets[k] = v }, { server.url("/v1").toString() })
            val catalog = ProviderValidation(AgentProvider.OPENROUTER, ValidationStatus.VALID,
                listOf(ProviderModel("deepseek/deepseek-v4.1-flash", "DeepSeek"), ProviderModel("openrouter/free", "Free", true)))
            val result = service.saveSelection(AgentProvider.OPENROUTER, "test-only-key", "deepseek/deepseek-v4.1-flash", catalog)
            assertTrue(result.inferenceVerified)
            assertEquals("openrouter/free", settings.settings.value.openrouterModel)
            assertTrue(result.message.contains("credit", ignoreCase = true))
            assertEquals("deepseek/deepseek-v4.1-flash", org.json.JSONObject(server.takeRequest().body.readUtf8()).getString("model"))
            assertEquals("openrouter/free", org.json.JSONObject(server.takeRequest().body.readUtf8()).getString("model"))
        } finally { server.shutdown() }
    }

    @Test fun `failed inference cannot save a credential or mark provider ready`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        try {
            val settings = AppSettings()
            val secrets = mutableMapOf<String, String>()
            val service = ProviderSetupService(settings, { k, v -> secrets[k] = v }, { server.url("/v1").toString() })
            val catalog = ProviderValidation(AgentProvider.GROQ, ValidationStatus.VALID,
                listOf(ProviderModel("openai/gpt-oss-120b", "GPT OSS")))
            val result = service.saveSelection(AgentProvider.GROQ, "test-only-key", "openai/gpt-oss-120b", catalog)
            assertFalse(result.inferenceVerified)
            assertTrue(secrets.isEmpty())
            assertFalse(service.isReady { secrets[it] })
        } finally { server.shutdown() }
    }

    @Test fun `OpenRouter funded account defaults to requested advertised DeepSeek model`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":{"is_free_tier":false,"limit_remaining":12}}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"other"},{"id":"deepseek/deepseek-v4.1-flash"}]}"""))
        server.start()
        try {
            val result = ProviderSetupService(AppSettings(), { _,_ -> }, { server.url("/v1").toString() }).validate(AgentProvider.OPENROUTER, "test-only-key")
            assertEquals("deepseek/deepseek-v4.1-flash", result.defaultModel)
        } finally { server.shutdown() }
    }

    @Test fun `a 400 save retries the provider model convention and reports the provider reason`() = runTest {
        val server = MockWebServer()
        // The chat endpoint rejects the output budget, then the bare catalog id, then accepts the prefix.
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"unsupported parameter max_tokens"}}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"model must be prefixed: opencode-go/<id>"}}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        server.start()
        try {
            val settings = AppSettings()
            val secrets = mutableMapOf<String, String>()
            val service = ProviderSetupService(settings, { k, v -> secrets[k] = v }, { server.url("/v1").toString() })
            val catalog = ProviderValidation(AgentProvider.OPENCODE_GO, ValidationStatus.VALID,
                listOf(ProviderModel("deepseek-v4.1-flash", "DeepSeek")))
            val result = service.saveSelection(AgentProvider.OPENCODE_GO, "test-only-key", "deepseek-v4.1-flash", catalog)
            assertTrue(result.message, result.inferenceVerified)
            val first = org.json.JSONObject(server.takeRequest().body.readUtf8())
            val second = org.json.JSONObject(server.takeRequest().body.readUtf8())
            val third = org.json.JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("deepseek-v4.1-flash", first.getString("model"))
            assertTrue(first.has("max_tokens"))
            assertFalse("second attempt drops the output budget", second.has("max_tokens"))
            assertEquals("opencode-go/deepseek-v4.1-flash", third.getString("model"))
        } finally { server.shutdown() }
    }

    @Test fun `a rejected save reports the provider text and saves nothing`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"unsupported parameter max_tokens"}}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"unsupported parameter max_tokens"}}"""))
        server.start()
        try {
            val settings = AppSettings()
            val secrets = mutableMapOf<String, String>()
            val service = ProviderSetupService(settings, { k, v -> secrets[k] = v }, { server.url("/v1").toString() })
            val catalog = ProviderValidation(AgentProvider.GROQ, ValidationStatus.VALID, listOf(ProviderModel("openai/gpt-oss-120b", "GPT OSS")))
            val result = service.saveSelection(AgentProvider.GROQ, "test-only-key", "openai/gpt-oss-120b", catalog)
            assertFalse(result.inferenceVerified)
            assertTrue("provider reason must be visible: " + result.message, result.message.contains("unsupported parameter max_tokens"))
            assertTrue(secrets.isEmpty())
        } finally { server.shutdown() }
    }

    @Test fun `an existing saved key becomes active only after it really answers`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        server.start()
        try {
            val settings = AppSettings()
            val secrets = mutableMapOf("groq.api.key" to "stored-key")
            val service = ProviderSetupService(settings, { k, v -> secrets[k] = v }, { server.url("/v1").toString() }, { secrets[it] })
            assertTrue(service.hasSavedKey(AgentProvider.GROQ))
            assertEquals("openrouter", settings.settings.value.activeAgentProvider)
            val result = service.activateWithStoredKey(AgentProvider.GROQ)
            assertTrue(result.inferenceVerified)
            assertEquals("groq", settings.settings.value.activeAgentProvider)
            assertTrue(service.isReady { secrets[it] })
            assertEquals("stored-key", server.takeRequest().getHeader("Authorization")!!.removePrefix("Bearer "))
        } finally { server.shutdown() }
    }

    @Test fun `a missing stored key never changes the active provider`() = runTest {
        val settings = AppSettings()
        val service = ProviderSetupService(settings, { _, _ -> }, { "http://127.0.0.1:1/v1" }, { null })
        assertFalse(service.hasSavedKey(AgentProvider.OPENCODE_GO))
        val result = service.activateWithStoredKey(AgentProvider.OPENCODE_GO)
        assertFalse(result.inferenceVerified)
        assertEquals("openrouter", settings.settings.value.activeAgentProvider)
    }

    @Test fun `shared backend reads selected provider and model on every turn and retains tool calls`() = runTest {
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"browser__read","arguments":"{}"}}]}}]}""")) }
        server.start()
        try {
            val settings = AppSettings(AppSettings.Settings(activeAgentProvider = "groq"))
            val service = ProviderSetupService(settings, { _,_ -> }, { server.url("/v1").toString() })
            val backend = SelectedProviderBackend(settings, service) { "test-only-key" }
            val message = backend.complete(org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "hello")), emptyList())
            assertEquals("call-1", message.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            assertEquals("openai/gpt-oss-120b", org.json.JSONObject(server.takeRequest().body.readUtf8()).getString("model"))
            settings.update { it.copy(activeAgentProvider = "opencode-go", opencodegoModel = "deepseek-v4.1-flash") }
            backend.complete(org.json.JSONArray(), emptyList())
            assertEquals("deepseek-v4.1-flash", org.json.JSONObject(server.takeRequest().body.readUtf8()).getString("model"))
        } finally { server.shutdown() }
    }

    @Test fun `selection is persisted only after chosen model inference succeeds`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"openai/gpt-oss-120b"}]}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        server.start()
        try {
            val settings = AppSettings()
            val secrets = mutableMapOf<String,String>()
            val service = ProviderSetupService(settings, { k,v -> secrets[k] = v }, { server.url("/v1").toString() })
            assertFalse(service.isReady { secrets[it] })
            val catalog = service.validate(AgentProvider.GROQ, "test-only-key")
            assertFalse(service.isReady { secrets[it] })
            val saved = service.saveSelection(AgentProvider.GROQ, "test-only-key", "openai/gpt-oss-120b", catalog)
            assertTrue(saved.inferenceVerified)
            assertTrue(service.isReady { secrets[it] })
            val restored = AppSettings.Settings.fromJson(settings.settings.value.toJson())
            assertEquals("groq", restored.activeAgentProvider)
            assertEquals("openai/gpt-oss-120b", restored.groqModel)
            assertFalse(restored.toJson().toString().contains("test-only-key"))
            server.takeRequest()
            val inference = org.json.JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("openai/gpt-oss-120b", inference.getString("model"))
            assertTrue("Reasoning models need enough output budget to produce a final answer", inference.getInt("max_tokens") >= 512)
        } finally { server.shutdown() }
    }

    @Test fun `OpenRouter authenticates key and zero credit defaults only to free models`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":{"is_free_tier":true,"limit_remaining":0}}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"paid","pricing":{"prompt":"1","completion":"1"}},{"id":"free","pricing":{"prompt":"0","completion":"0"}}]}"""))
        server.start()
        try {
            val result = ProviderSetupService(AppSettings(), { _, _ -> }, { server.url("/v1").toString() }).validate(AgentProvider.OPENROUTER, "test-only-key")
            assertEquals("/v1/key", server.takeRequest().path)
            assertEquals("free", result.defaultModel)
            assertFalse(result.inferenceVerified)
        } finally { server.shutdown() }
    }

    @Test fun `Groq validates bearer key against models and resolves advertised requested id`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"openai/gpt-oss-120b"}]}"""))
        server.start()
        try {
            val service = ProviderSetupService(AppSettings(), { _, _ -> }, { server.url("/v1").toString() })
            val result = service.validate(AgentProvider.GROQ, "test-only-key")
            assertEquals(ValidationStatus.VALID, result.status)
            assertEquals("openai/gpt-oss-120b", result.defaultModel)
            val request = server.takeRequest()
            assertEquals("/v1/models", request.path)
            assertEquals("Bearer test-only-key", request.getHeader("Authorization"))
            assertFalse(result.toString().contains("test-only-key"))
        } finally { server.shutdown() }
    }
}
