package com.mcpintelligence.fr3k.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.ConsentLevel
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import kotlinx.coroutines.launch

/**
 * Settings screen. Live surface over [AppSettings].
 *
 * Controls:
 *   - HUD enabled
 *   - HUD edge margin
 *   - Consent profile (LOCAL_ONLY / PRIVATE / NORMAL / RESEARCH)
 *   - Hermes endpoint
 *   - Telemetry enabled
 *   - Experimental features
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Fr3kTheme { SettingsScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun SettingsScreen(onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    val settings by app.settings.settings.collectAsState()
    val contextEngine = app.fr3kCore.contextEngine
    val ctx by contextEngine.current.collectAsState()

    var endpoint by remember(settings.hermesEndpoint) { mutableStateOf(settings.hermesEndpoint) }
    var telemetry by remember(settings.telemetryEnabled) { mutableStateOf(settings.telemetryEnabled) }

    Box(modifier = Modifier.fillMaxSize().background(Fr3kPalette.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            com.mcpintelligence.fr3k.ui.FeatureGuide("Settings")

            Text(
                text = "SETTINGS",
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                app.startActivity(android.content.Intent(app, com.mcpintelligence.fr3k.ui.onboarding.OnboardingActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }, modifier = Modifier.fillMaxWidth()) { Text("REOPEN COMPLETE DEVICE ONBOARDING") }

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "HUD") {
                Column {
                    Toggle("HUD overlay enabled", settings.hudEnabled) { v ->
                        app.settings.update { it.copy(hudEnabled = v) }
                        if (!v) app.stopService(android.content.Intent(app, com.mcpintelligence.fr3k.hud.HudOverlayService::class.java))
                        else if (android.provider.Settings.canDrawOverlays(app)) app.startForegroundService(android.content.Intent(app, com.mcpintelligence.fr3k.hud.HudOverlayService::class.java))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("edge margin: ${settings.hudEdgeMarginDp} dp", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Slider(
                        value = settings.hudEdgeMarginDp.toFloat(),
                        onValueChange = { v -> app.settings.update { it.copy(hudEdgeMarginDp = v.toInt()) } },
                        modifier = Modifier.semantics { contentDescription = "HUD edge margin" },
                        valueRange = 0f..48f,
                        steps = 16,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "consent profile") {
                Column {
                    listOf(
                        ConsentLevel.LOCAL_ONLY to "LOCAL ONLY · no data leaves the device",
                        ConsentLevel.PRIVATE to "PRIVATE · review shared fields",
                        ConsentLevel.NORMAL to "NORMAL · default",
                        ConsentLevel.RESEARCH to "RESEARCH · web tools allowed",
                    ).forEach { (level, label) ->
                        RadioRow(level == settings.consentProfile, label) {
                            app.settings.update { it.copy(consentProfile = level) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "Hermes") {
                Column {
                    Text("endpoint:", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    androidx.compose.material3.OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { app.settings.update { it.copy(hermesEndpoint = endpoint) } },
                        colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("SAVE ENDPOINT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "auth token: ${if (app.secureStore.get(settings.hermesAuthTokenKey) != null) "stored (encrypted)" else "not set"}",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "AI provider — which brain answers") {
                val scope = rememberCoroutineScope()
                val openCode = app.openCodeProvider
                val stored = app.secureStore.get(settings.opencodeApiKeyKey)
                var openCodeKeyInput by remember { mutableStateOf("") }
                var status by remember { mutableStateOf<String?>(null) }
                var modelExpanded by remember { mutableStateOf(false) }
                var modelSearch by remember { mutableStateOf("") }
                var liveModels by remember { mutableStateOf(openCode.availableModels()) }
                Column {
                    Text(
                        text = "active: ${app.activeProviderLabel}  ·  model ${app.activeModelLabel}",
                        color = Fr3kPalette.Accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "With no keys at all, OpenCode Go answers on the public free tier. Saving an " +
                            "OpenCode key replaces OpenRouter everywhere in the app — chat bubble, mini browser, " +
                            "shared agent session and model picker. OpenCode wins even if both keys are stored.",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "opencode key: " + (stored?.let { "stored (encrypted) — ${maskKey(it)}" } ?: "not set"),
                        color = if (stored != null) Fr3kPalette.Ok else Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = openCodeKeyInput,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        label = { Text("OpenCode Go API key") },
                        onValueChange = { openCodeKeyInput = it },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        placeholder = { Text("paste your OpenCode key", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = {
                                val trimmed = openCodeKeyInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    app.secureStore.put(settings.opencodeApiKeyKey, trimmed)
                                    openCodeKeyInput = ""
                                    status = "key saved — OpenCode now answers everywhere"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                            modifier = Modifier.semantics { contentDescription = "settings-go-save" },
                        ) { Text("SAVE KEY", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = {
                                app.secureStore.remove(settings.opencodeApiKeyKey)
                                status = "key removed — the key rules re-evaluate immediately"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
                            modifier = Modifier.semantics { contentDescription = "settings-go-remove" },
                        ) { Text("REMOVE", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                        Button(
                            onClick = {
                                scope.launch {
                                    status = "checking…"
                                    val health = openCode.health()
                                    status = if (health.online) {
                                        "ONLINE — ${health.model ?: "?"} — ${health.message ?: ""}"
                                    } else {
                                        "OFFLINE — ${health.message ?: "no answer"}"
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
                            modifier = Modifier.semantics { contentDescription = "settings-go-test" },
                        ) { Text("TEST", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("preference:", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    // Stacked and full width: a horizontal row clipped its third option ("OPENROUTER") at the
                    // panel edge at every font scale, so the choice was partly unreachable.
                    Column(modifier = Modifier.fillMaxWidth()) {
                        listOf(
                            com.mcpintelligence.fr3k.core.AiProviderPreference.AUTO to "AUTO (follow keys)",
                            com.mcpintelligence.fr3k.core.AiProviderPreference.OPENCODE to "OPENCODE",
                            com.mcpintelligence.fr3k.core.AiProviderPreference.OPENROUTER to "OPENROUTER",
                        ).forEach { (value, label) ->
                            val selected = settings.aiProviderPreference == value
                            Button(
                                onClick = {
                                    app.settings.update { it.copy(aiProviderPreference = value) }
                                    status = "preference: $label"
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) Fr3kPalette.Accent else Fr3kPalette.Surface,
                                    contentColor = if (selected) Fr3kPalette.Bg else Fr3kPalette.Text,
                                ),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            ) { Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "model: ${openCode.selectedModel()}  (tap to switch)",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    Button(onClick = { modelExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("CHOOSE MODEL ▼  (${liveModels.size} listed)", fontFamily = FontFamily.Monospace)
                    }
                    Button(onClick = {
                        scope.launch {
                            status = "Fetching current OpenCode catalogue..."
                            openCode.refreshModels().fold(
                                onSuccess = {
                                    liveModels = openCode.availableModels()
                                    status = "${liveModels.size} available catalogue entries loaded"
                                },
                                onFailure = { status = "Model refresh failed: ${it.message}; showing cached models" },
                            )
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("REFRESH ALL AVAILABLE MODELS") }
                    DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false },
                        modifier = Modifier.heightIn(max = 440.dp)) {
                        androidx.compose.material3.OutlinedTextField(
                            value = modelSearch, onValueChange = { modelSearch = it },
                            label = { Text("Filter models") }, modifier = Modifier.width(300.dp),
                        )
                        liveModels.filter { it.id.contains(modelSearch, true) }.forEach { model ->
                            DropdownMenuItem(text = { Text(model.id + if (model.isFree) " · FREE" else "") },
                                onClick = {
                                    openCode.setModel(model.id)
                                    app.settings.update { it.copy(opencodeModel = model.id) }
                                    status = "Selected: ${model.id} (verify chat support with TEST)"
                                    modelExpanded = false
                                })
                        }
                    }
                    status?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "OpenRouter (fallback)") {
                val scope = rememberCoroutineScope()
                val provider = app.openRouterProvider
                val storedKey = app.secureStore.get(settings.openrouterApiKeyKey)
                var keyInput by remember { mutableStateOf("") }
                var modelInput by remember(provider.selectedModel()) { mutableStateOf(provider.selectedModel()) }
                var status by remember { mutableStateOf<String?>(null) }
                var allModels by remember { mutableStateOf(provider.cachedAllModels()) }
                var allExpanded by remember { mutableStateOf(false) }
                var allSearch by remember { mutableStateOf("") }
                Column {
                    Text(
                        text = "key: ${if (storedKey != null) "stored (encrypted) — ${maskKey(storedKey)}" else "not set"}",
                        color = if (storedKey != null) Fr3kPalette.Ok else Fr3kPalette.Warn,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = keyInput,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        label = { Text("OpenRouter API key") },
                        onValueChange = { keyInput = it },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        placeholder = { Text("sk-or-…", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = {
                                val trimmed = keyInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    app.secureStore.put(settings.openrouterApiKeyKey, trimmed)
                                    keyInput = ""
                                    status = "key saved"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                            modifier = Modifier.semantics { contentDescription = "settings-router-save" },
                        ) { Text("SAVE KEY", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = { app.secureStore.remove(settings.openrouterApiKeyKey); status = "key removed" },
                            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
                            modifier = Modifier.semantics { contentDescription = "settings-router-remove" },
                        ) { Text("REMOVE", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                        Button(
                            onClick = {
                                scope.launch {
                                    status = "validating…"
                                    val h = provider.health()
                                    when {
                                        h.online -> status = "ONLINE — ${h.model ?: "?"} — ${h.message}"
                                        else -> status = "OFFLINE — ${h.message}"
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
                            modifier = Modifier.semantics { contentDescription = "settings-router-validate" },
                        ) { Text("VALIDATE", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    }
                    status?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = if (it.startsWith("ONLINE") || it == "key saved") Fr3kPalette.Ok else Fr3kPalette.Err, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("model: ${provider.selectedModel()}", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    androidx.compose.material3.OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = {
                                val m = modelInput.trim()
                                if (m.isNotEmpty()) {
                                    provider.setModel(m)
                                    app.settings.update { it.copy(openrouterModel = m) }
                                    status = "model set"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                            modifier = Modifier.semantics { contentDescription = "settings-router-model" },
                        ) { Text("USE MODEL", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = {
                                provider.setModel(com.mcpintelligence.fr3k.integrations.openrouter.OpenRouterProvider.DEFAULT_MODEL)
                                app.settings.update { it.copy(openrouterModel = com.mcpintelligence.fr3k.integrations.openrouter.OpenRouterProvider.DEFAULT_MODEL) }
                                modelInput = com.mcpintelligence.fr3k.integrations.openrouter.OpenRouterProvider.DEFAULT_MODEL
                                status = "free model selected"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
                            modifier = Modifier.semantics { contentDescription = "settings-router-free" },
                        ) { Text("USE FREE", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                        Button(
                            onClick = {
                                scope.launch {
                                    status = "refreshing…"
                                    provider.refreshAllModels().fold(
                                        onSuccess = { allModels = it; status = "${it.size} models loaded" },
                                        onFailure = { status = "Could not fetch models: ${it.message}" },
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
                            modifier = Modifier.semantics { contentDescription = "settings-router-refresh" },
                        ) { Text("REFRESH", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    }
                    Button(onClick = { allExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("CHOOSE ANY AVAILABLE MODEL ▼ (${allModels.size})")
                    }
                    DropdownMenu(expanded = allExpanded, onDismissRequest = { allExpanded = false },
                        modifier = Modifier.heightIn(max = 440.dp)) {
                        androidx.compose.material3.OutlinedTextField(
                            value = allSearch, onValueChange = { allSearch = it },
                            label = { Text("Search full catalogue") }, modifier = Modifier.width(300.dp),
                        )
                        allModels.filter { it.id.contains(allSearch, true) || it.name.contains(allSearch, true) }
                            .forEach { m ->
                                DropdownMenuItem(text = { Text(m.name + if (m.free) " · FREE" else " · MAY BE PAID") },
                                    onClick = {
                                        provider.setModel(m.id)
                                        app.settings.update { it.copy(openrouterModel = m.id) }
                                        modelInput = m.id
                                        status = "Selected: ${m.id}; check tool support and billing"
                                        allExpanded = false
                                    })
                            }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "voice assistant") {
                VoiceAssistantPanel()
            }

            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "background voice") {
                BatteryOptimisationPanel()
            }

            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "blackwave pairing") {
                com.mcpintelligence.fr3k.ui.blackwave.BlackwavePairingPanel()
            }

            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "telemetry") {
                Column {
                    Toggle("telemetry enabled (crash-free only)", telemetry) { v -> telemetry = v; app.settings.update { it.copy(telemetryEnabled = v) } }
                }
            }

            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "current context") {
                Column {
                    Text(ctx.summary(), color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { contextEngine.clear() },
                        colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("CLEAR CONTEXT", fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("CLOSE", fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Switch(checked = value, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun RadioRow(active: Boolean, label: String, onClick: () -> Unit) {
    val bg = if (active) Fr3kPalette.AccentDim else Fr3kPalette.Surface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .heightIn(min = 48.dp).clickable { onClick() }
            // The clickable node itself must carry the label: a screen reader announces this node, and the
            // child text is not exposed on it in the accessibility tree.
            .semantics { contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = (if (active) "● " else "○ ") + label,
            color = if (active) Fr3kPalette.Bg else Fr3kPalette.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

/**
 * Mask an API key for display: `sk-or-…abcd`. Never render the full key —
 * the spec (ANDROID-TESTING-PASS §5) requires masked key display.
 */
private fun maskKey(key: String): String {
    if (key.length <= 8) return "••••"
    return key.takeLast(4).let { tail -> "${key.take(6)}…$tail" }
}
