package com.mcpintelligence.fr3k.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.AiProviderPreference
import com.mcpintelligence.fr3k.core.tools.AgentSessionBackend
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The operator's rule, proven on a real device: when an OpenCode key is stored it takes over the whole
 * app from OpenRouter, and the shared agent session — which is what the chat bubble and the mini
 * browser both use — answers from OpenCode immediately, without a restart.
 */
@RunWith(AndroidJUnit4::class)
class ProviderPreferenceDeviceTest {

    private val app get() = Fr3kApplication.get()
    private val settings get() = app.settings.settings.value
    private var openCodeKeyBackup: String? = null
    private var openRouterKeyBackup: String? = null
    private var preferenceBackup: String = AiProviderPreference.AUTO

    @Before fun rememberKeys() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        openCodeKeyBackup = app.secureStore.get(settings.opencodeApiKeyKey)
        openRouterKeyBackup = app.secureStore.get(settings.openrouterApiKeyKey)
        preferenceBackup = settings.aiProviderPreference
    }

    @After fun restoreKeys() {
        // Leave the operator's device exactly as found, whether the test passed or failed.
        restore(settings.opencodeApiKeyKey, openCodeKeyBackup)
        restore(settings.openrouterApiKeyKey, openRouterKeyBackup)
        app.settings.update { it.copy(aiProviderPreference = preferenceBackup) }
    }

    private fun restore(key: String, value: String?) {
        if (value == null) app.secureStore.remove(key) else app.secureStore.put(key, value)
    }

    private fun clearKeys() {
        app.secureStore.remove(settings.opencodeApiKeyKey)
        app.secureStore.remove(settings.openrouterApiKeyKey)
    }

    @Test fun anOpencodeKeyTakesOverEverySurfaceAndRemovingItGivesThemBack() {
        app.settings.update { it.copy(aiProviderPreference = AiProviderPreference.AUTO) }
        clearKeys()
        assertEquals(
            "with no keys the zero-key OpenCode path answers",
            AiProviderPreference.OPENCODE,
            app.activeProviderId,
        )
        assertTrue("the chat and browser session must follow", app.isOpenCodeActive())
        assertTrue(app.activeProviderLabel.contains("zero-key"))

        app.secureStore.put(settings.openrouterApiKeyKey, "sk-or-test-key-not-used-for-requests")
        assertEquals(
            "openrouter is used only while it is the only key",
            AiProviderPreference.OPENROUTER,
            app.activeProviderId,
        )
        assertFalse(app.isOpenCodeActive())

        app.secureStore.put(settings.opencodeApiKeyKey, "opencode-test-key-not-used-for-requests")
        assertEquals(
            "an opencode key replaces openrouter everywhere",
            AiProviderPreference.OPENCODE,
            app.activeProviderId,
        )
        assertTrue("the live session must switch without a restart", app.isOpenCodeActive())
        assertEquals("the backend is the OpenCode provider itself", "opencode-go", app.routingProvider.id)
        assertEquals("the label names the key it is using", "OpenCode Go (your key)", app.routingProvider.displayName)

        app.secureStore.remove(settings.opencodeApiKeyKey)
        assertEquals("removing the key returns to openrouter", AiProviderPreference.OPENROUTER, app.activeProviderId)
        assertEquals("openrouter", app.routingProvider.id)

        // An explicit operator choice outranks both keys.
        app.secureStore.put(settings.opencodeApiKeyKey, "opencode-test-key-not-used-for-requests")
        app.settings.update { it.copy(aiProviderPreference = AiProviderPreference.OPENROUTER) }
        assertEquals(AiProviderPreference.OPENROUTER, app.activeProviderId)
        app.settings.update { it.copy(aiProviderPreference = AiProviderPreference.AUTO) }
    }

    @Test fun opencodeCanActuallyServeTheToolCallingSession() {
        // The shared session needs native tool calls, not a text completion. Without this the chat and
        // the mini browser would break the moment OpenCode took over.
        assertTrue(
            "OpenCode must implement the agent-session backend",
            app.openCodeProvider is AgentSessionBackend,
        )
        assertTrue(
            "the router must present itself as a session backend too",
            app.routingProvider is AgentSessionBackend,
        )
    }

    @Test fun theRouterDelegatesToWhicheverProviderIsChosen() {
        // Force the OpenRouter path with no key at all: it must refuse before any network call, which
        // proves the router really reached that provider rather than answering from somewhere else.
        clearKeys()
        app.settings.update { it.copy(aiProviderPreference = AiProviderPreference.OPENROUTER) }
        val router = app.routingProvider as AgentSessionBackend
        val failure = runCatching { runBlocking { router.complete(JSONArray(), emptyList()) } }.exceptionOrNull()
        assertTrue("expected a clean refusal, got $failure", failure is IllegalStateException)
        assertTrue(
            "the refusal must name the missing configuration: ${failure?.message}",
            failure?.message.orEmpty().contains("not configured"),
        )
        app.settings.update { it.copy(aiProviderPreference = AiProviderPreference.AUTO) }
    }
}
