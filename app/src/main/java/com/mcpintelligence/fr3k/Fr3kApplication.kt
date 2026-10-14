package com.mcpintelligence.fr3k

import android.app.Application
import android.content.Intent
import android.util.Log
import com.mcpintelligence.fr3k.core.CapabilityRegistry
import com.mcpintelligence.fr3k.core.CommandRegistry
import com.mcpintelligence.fr3k.core.DeviceIdentity
import com.mcpintelligence.fr3k.core.DeviceRegistry
import com.mcpintelligence.fr3k.core.Fr3kCore
import com.mcpintelligence.fr3k.core.PluginManager
import com.mcpintelligence.fr3k.core.SecureStore
import com.mcpintelligence.fr3k.core.AppSettings
import com.mcpintelligence.fr3k.core.AutomationEngine
import com.mcpintelligence.fr3k.core.ContextEngine
import com.mcpintelligence.fr3k.core.DeviceHandoffAdapter
import com.mcpintelligence.fr3k.core.GpsPlugin
import com.mcpintelligence.fr3k.core.ShareCommandsPlugin
import com.mcpintelligence.fr3k.core.SystemPlugin
import com.mcpintelligence.fr3k.core.VoiceIntentPlanner
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveBridgeClient
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwavePlugin
import com.mcpintelligence.fr3k.integrations.hermes.AiProviderRegistry
import com.mcpintelligence.fr3k.integrations.hermes.HermesAskCommand
import com.mcpintelligence.fr3k.integrations.hermes.HermesPlugin
import com.mcpintelligence.fr3k.integrations.hermes.HermesProvider

/**
 * Application class — process entry point. Assembles the FR3K core and
 * starts plugins. Held as a singleton in [fr3kCore].
 *
 * V2 adds: Meshtastic / MeshCore adapters, GPS, Termux job runner.
 * V3 adds: Rootless LSPatch/NPatch and rooted Vector adapters.
 */
class Fr3kApplication : Application() {

    private val capabilityRegistryImpl by lazy { CapabilityRegistry() }
    private val commandRegistryImpl by lazy { CommandRegistry() }
    private val deviceRegistryImpl by lazy { DeviceRegistry() }
    private val settingsImpl by lazy {
        val preferences = getSharedPreferences("fr3k.settings", MODE_PRIVATE)
        AppSettings.open(
            read = { preferences.getString("settings.v1", null) },
            write = { encoded -> preferences.edit().putString("settings.v1", encoded).apply() },
        )
    }
    private val contextEngineImpl by lazy { ContextEngine() }
    private val automationEngineImpl by lazy { AutomationEngine() }

    val identity by lazy { DeviceIdentity(this) }
    val secureStore by lazy { SecureStore(this) }
    val aiProviders by lazy { AiProviderRegistry() }
    val termuxBridge by lazy { com.mcpintelligence.fr3k.integrations.termux.TermuxBridge(this) }
    val agentToolBus by lazy { com.mcpintelligence.fr3k.core.tools.AgentToolBus() }
    val openRouterProvider by lazy {
        com.mcpintelligence.fr3k.integrations.openrouter.OpenRouterProvider(
            apiKeyProvider = { secureStore.get(settingsImpl.settings.value.openrouterApiKeyKey) },
            defaultModel = settingsImpl.settings.value.openrouterModel,
        )
    }

    /**
     * One routing session id for this process, so the chat bubble, the mini browser and the tool calls
     * all stay on the same OpenCode Go route instead of each looking like a new client.
     */
    private val routingSessionId: String by lazy { "hud-" + java.util.UUID.randomUUID() }

    /** OpenCode Go: the operator's key when configured, the subscription endpoint either way. */
    val openCodeProvider by lazy {
        com.mcpintelligence.fr3k.integrations.opencode.OpenCodeGoProvider(
            bearerTokenProvider = { secureStore.get(settingsImpl.settings.value.opencodeApiKeyKey).orEmpty() },
            sessionIdProvider = { routingSessionId },
            defaultModel = settingsImpl.settings.value.opencodeModel
                .takeIf { it.isNotBlank() }
                ?: com.mcpintelligence.fr3k.integrations.opencode.OpenCodeGoProvider.DEFAULT_MODEL,
        )
    }

    /**
     * The single decision point for which provider answers, used by the chat bubble, the mini browser,
     * the shared agent session and the model picker. Re-evaluated on every call, so saving an OpenCode
     * key in Settings takes over the whole app immediately rather than at the next process start.
     */
    val activeProviderId: String
        get() = com.mcpintelligence.fr3k.core.AiProviderPreference.resolve(
            explicit = settingsImpl.settings.value.aiProviderPreference,
            openCodeKeyConfigured = secureStore.get(settingsImpl.settings.value.opencodeApiKeyKey) != null,
            openRouterKeyConfigured = secureStore.get(settingsImpl.settings.value.openrouterApiKeyKey) != null,
        )

    fun isOpenCodeActive(): Boolean =
        activeProviderId == com.mcpintelligence.fr3k.core.AiProviderPreference.OPENCODE

    val activeProvider: com.mcpintelligence.fr3k.core.AiProvider
        get() = if (isOpenCodeActive()) openCodeProvider else openRouterProvider

    val activeModelLabel: String
        get() = if (isOpenCodeActive()) openCodeProvider.selectedModel() else openRouterProvider.selectedModel()

    /** "OpenCode Go (your key)" / "OpenCode Go (zero-key)" / "OpenRouter" — never a bare name. */
    val activeProviderLabel: String
        get() = if (isOpenCodeActive())
            "OpenCode Go" + if (openCodeProvider.usingConfiguredKey()) " (your key)" else " (zero-key free tier)"
        else "OpenRouter"

    /**
     * Delegates every call to whichever provider the key rules select right now. The shared agent
     * session holds this for the life of the process, so switching providers must not require a restart.
     */
    inner class ActiveProviderRouter :
        com.mcpintelligence.fr3k.core.AiProvider,
        com.mcpintelligence.fr3k.core.tools.AgentSessionBackend {
        override val id: String get() = activeProvider.id
        override val displayName: String get() = activeProviderLabel
        override val capabilities: Set<String> get() = activeProvider.capabilities
        override val requiresNetwork: Boolean = true
        override val requiresApiKey: Boolean = false
        override suspend fun ask(request: com.mcpintelligence.fr3k.protocol.AgentAskRequest): com.mcpintelligence.fr3k.protocol.AgentAskResponse {
            check(settings.settings.value.consentProfile != com.mcpintelligence.fr3k.core.ConsentLevel.LOCAL_ONLY) { "Local-only consent is enabled; nothing sent to an AI provider." }
            return activeProvider.ask(request)
        }
        override suspend fun health() = activeProvider.health()
        override suspend fun complete(
            messages: org.json.JSONArray,
            tools: List<com.mcpintelligence.fr3k.core.tools.AgentTool>,
        ): org.json.JSONObject {
            check(settings.settings.value.consentProfile != com.mcpintelligence.fr3k.core.ConsentLevel.LOCAL_ONLY) { "Local-only consent is enabled; nothing sent to an AI provider." }
            val chosen = activeProvider
            val backend = chosen as? com.mcpintelligence.fr3k.core.tools.AgentSessionBackend
                ?: throw IllegalStateException("$activeProviderLabel cannot run the shared agent session")
            return backend.complete(messages, tools)
        }
    }

    /** Typed for the session constructor, which wants a backend rather than a plain provider. */
    val routingProvider: ActiveProviderRouter by lazy { ActiveProviderRouter() }

    /** Userland app layer: registry, journal, archive and workflows in one place. */
    val capabilityHost: com.mcpintelligence.fr3k.appcontrol.CapabilityHost by lazy {
        com.mcpintelligence.fr3k.appcontrol.CapabilityHost(this)
    }

    /** Localhost-only capability surface, started on demand and bounded by the process lifetime. */
    val workflowSession by lazy { com.mcpintelligence.fr3k.workflow.WorkflowSession(capabilityHost) }

    val mcpServer: com.mcpintelligence.fr3k.mcp.Fr3kMcpServer by lazy {
        com.mcpintelligence.fr3k.mcp.Fr3kMcpServer(capabilityHost)
    }

    @Synchronized
    fun startMcp(): Int = if (mcpServer.running) mcpServer.port() else mcpServer.start()

    @Synchronized
    fun stopMcp() {
        if (mcpServer.running) mcpServer.stop()
    }

    val agentSession by lazy {
        agentToolBus.register(com.mcpintelligence.fr3k.core.appcontrol.AppControlPlanTool())
        agentToolBus.register(com.mcpintelligence.fr3k.appcontrol.OwnedFixtureAgentTool(
            com.mcpintelligence.fr3k.appcontrol.OwnedFixtureController(this)))
        agentToolBus.register(com.mcpintelligence.fr3k.core.appcontrol.AppControlInspectTool {
            com.mcpintelligence.fr3k.appcontrol.AndroidPackageInspector(this).inspect(it)
        })
        agentToolBus.register(com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveAgentTool(blackwaveBridgeClient))
        // The routing provider, not a fixed backend: chat and browser follow the key rules live.
        com.mcpintelligence.fr3k.core.tools.SharedAgentSession(agentToolBus, routingProvider, identity.deviceId)
    }

    /** Hoisted like [openRouterProvider] so the §10 diagnostics panel can probe it directly. */
    val blackwaveBridgeClient by lazy {
        BlackwaveBridgeClient(
            endpointProvider = { settingsImpl.settings.value.blackwaveEndpoint },
            credentialProvider = { secureStore.get(settingsImpl.settings.value.blackwaveCredentialKey) },
            clientIdProvider = { settingsImpl.settings.value.blackwaveClientId },
        )
    }

    val fr3kCore: Fr3kCore by lazy {
        val pluginManager = PluginManager(
            capabilityRegistry = capabilityRegistryImpl,
            commandRegistry = commandRegistryImpl,
        )
        Fr3kCore(
            appContext = applicationContext,
            identity = identity,
            secureStore = secureStore,
            capabilityRegistry = capabilityRegistryImpl,
            commandRegistry = commandRegistryImpl,
            deviceRegistry = deviceRegistryImpl,
            settings = settingsImpl,
            contextEngine = contextEngineImpl,
            automationEngine = automationEngineImpl,
            deviceHandoff = DeviceHandoffAdapter(),
            pluginManager = pluginManager,
        )
    }

    val capabilityRegistry get() = capabilityRegistryImpl
    val commandRegistry get() = commandRegistryImpl
    val deviceRegistry get() = deviceRegistryImpl
    val settings get() = settingsImpl
    val contextEngine get() = contextEngineImpl
    val automationEngine get() = automationEngineImpl

    val voiceIntentPlanner by lazy { VoiceIntentPlanner { deviceRegistryImpl } }

    override fun onCreate() {
        super.onCreate()
        instance = this
        bootstrap()

    }

    private fun bootstrap() {
        Log.i(TAG, "FR3K booting on deviceId=${identity.deviceId} android=${identity.androidId} v${identity.appVersion}")
        registerHermes()
        registerOpenCode()
        registerOpenRouter()
        registerBlackwave()
        fr3kCore.pluginManager.register(GpsPlugin(com.mcpintelligence.fr3k.location.LocationActions(this)::execute))
        fr3kCore.pluginManager.register(ShareCommandsPlugin(
            contextEngineProvider = { contextEngineImpl },
            deviceRegistryProvider = { deviceRegistryImpl },
            transform = { operation, text ->
                routingProvider.ask(com.mcpintelligence.fr3k.protocol.AgentAskRequest(
                    prompt = "$operation:\n\n$text",
                    context = null,
                )).text
            },
        ))
        fr3kCore.pluginManager.register(SystemPlugin(com.mcpintelligence.fr3k.ui.AndroidStatusReader(this)::read))
        fr3kCore.pluginManager.startAll()
        seedAutomations()
    }

    private fun registerHermes() {
        val hermesProvider = HermesProvider(
            endpointProvider = { settingsImpl.settings.value.hermesEndpoint },
            authTokenProvider = { secureStore.get(settingsImpl.settings.value.hermesAuthTokenKey) },
            deviceIdProvider = { identity.deviceId },
        )
        val askCommand = HermesAskCommand(provider = { hermesProvider })
        fr3kCore.pluginManager.register(
            HermesPlugin(
                provider = hermesProvider,
                aiProviderRegistry = aiProviders,
                commandFactory = { askCommand },
            )
        )
    }

    /**
     * Register OpenCode Go as a second AI provider that defaults to
     * [OpenCodeGoProvider.DEFAULT_MODEL] = `big-pickle`. This is the
     * "no API key" free path: we talk to opencode.ai/zen with the public
     * bearer key, fetch the live free-model list, and let the user pick.
     */
    private fun registerOpenCode() {
        val askCommand = com.mcpintelligence.fr3k.integrations.opencode.AskOpenCodeCommand(
            provider = { openCodeProvider },
        )
        fr3kCore.pluginManager.register(
            com.mcpintelligence.fr3k.integrations.opencode.OpenCodePlugin(
                provider = openCodeProvider,
                aiProviderRegistry = aiProviders,
                commandFactory = { askCommand },
            )
        )
    }

    private fun registerOpenRouter() {
        val askCommand = com.mcpintelligence.fr3k.integrations.openrouter.AskOpenRouterCommand(
            provider = { openRouterProvider },
        )
        fr3kCore.pluginManager.register(
            com.mcpintelligence.fr3k.integrations.openrouter.OpenRouterPlugin(
                provider = openRouterProvider,
                aiProviderRegistry = aiProviders,
                commandFactory = { askCommand },
            )
        )
    }

    private fun registerBlackwave() {
        fr3kCore.pluginManager.register(
            BlackwavePlugin(bridgeClient = blackwaveBridgeClient)
        )
    }

    private fun seedAutomations() {
        val owner = "fr3k.seeds"
        fr3kCore.automationEngine.upsert(
            AutomationEngine.Automation(
                id = "share-received-ask",
                ownerId = owner,
                title = "Share received → Ask selected provider",
                trigger = AutomationEngine.Automation.Trigger(type = AutomationEngine.TriggerType.SHARE_RECEIVED),
                action = AutomationEngine.Action.OpenAskAboutThis(),
            )
        )
        fr3kCore.automationEngine.upsert(
            AutomationEngine.Automation(
                id = "url-shared-clean",
                ownerId = owner,
                title = "URL shared → clean URL",
                trigger = AutomationEngine.Automation.Trigger(type = AutomationEngine.TriggerType.URL_SHARED),
                action = AutomationEngine.Action.RunCommand(
                    commandId = "share.url.clean",
                    args = emptyMap(),
                ),
            )
        )
        fr3kCore.automationEngine.upsert(
            AutomationEngine.Automation(
                id = "url-shared-ask",
                ownerId = owner,
                title = "URL shared → Ask selected provider",
                trigger = AutomationEngine.Automation.Trigger(type = AutomationEngine.TriggerType.URL_SHARED),
                action = AutomationEngine.Action.OpenAskAboutThis(),
            )
        )
        fr3kCore.automationEngine.upsert(
            AutomationEngine.Automation(
                id = "browser-foreground-ask",
                ownerId = owner,
                title = "Browser foreground → Ask selected provider",
                trigger = AutomationEngine.Automation.Trigger(
                    type = AutomationEngine.TriggerType.FOREGROUND_APP,
                    packageMatch = "com.android.chrome",
                ),
                action = AutomationEngine.Action.OpenAskAboutThis(),
                enabled = false,  // off by default; user opts in
            )
        )
    }

    companion object {
        const val TAG = "FR3K.app"
        private lateinit var instance: Fr3kApplication
        fun get(): Fr3kApplication = instance
    }
}
