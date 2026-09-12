package com.mcpintelligence.fr3k.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.AppSettings
import com.mcpintelligence.fr3k.core.ConsentLevel
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveBridgeClient
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = "SETTINGS",
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "HUD") {
                Column {
                    Toggle("HUD overlay enabled", settings.hudEnabled) { v ->
                        app.settings.update { it.copy(hudEnabled = v) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("edge margin: ${settings.hudEdgeMarginDp} dp", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Slider(
                        value = settings.hudEdgeMarginDp.toFloat(),
                        onValueChange = { v -> app.settings.update { it.copy(hudEdgeMarginDp = v.toInt()) } },
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
                        ConsentLevel.PRIVATE to "PRIVATE · PII stripped",
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
                    Text("endpoint:", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    androidx.compose.material3.OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { app.settings.update { it.copy(hermesEndpoint = endpoint) } },
                        colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("SAVE ENDPOINT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    HermesTokenEditor(app, settings)
                }
            }

            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "BLACKWAVE") {
                BlackwaveSettingsEditor(app, settings)
            }

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "OpenRouter") {
                val scope = rememberCoroutineScope()
                val provider = app.openRouterProvider
                val storedKey = app.secureStore.get(settings.openrouterApiKeyKey)
                var keyInput by remember { mutableStateOf("") }
                var modelInput by remember(provider.selectedModel()) { mutableStateOf(provider.selectedModel()) }
                var status by remember { mutableStateOf<String?>(null) }
                var freeModels by remember { mutableStateOf<List<com.mcpintelligence.fr3k.integrations.openrouter.OpenRouterModel>>(emptyList()) }
                Column {
                    Text(
                        text = "key: ${if (storedKey != null) "stored (encrypted) — ${maskKey(storedKey)}" else "not set"}",
                        color = if (storedKey != null) Fr3kPalette.Ok else Fr3kPalette.Warn,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        placeholder = { Text("sk-or-…", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        ) { Text("SAVE KEY", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = { app.secureStore.remove(settings.openrouterApiKeyKey); status = "key removed" },
                            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
                        ) { Text("REMOVE", fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
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
                        ) { Text("VALIDATE", fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                    }
                    status?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = if (it.startsWith("ONLINE") || it == "key saved") Fr3kPalette.Ok else Fr3kPalette.Err, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("model: ${provider.selectedModel()}", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    androidx.compose.material3.OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        ) { Text("USE MODEL", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = {
                                provider.setModel(com.mcpintelligence.fr3k.integrations.openrouter.OpenRouterProvider.DEFAULT_MODEL)
                                app.settings.update { it.copy(openrouterModel = com.mcpintelligence.fr3k.integrations.openrouter.OpenRouterProvider.DEFAULT_MODEL) }
                                modelInput = com.mcpintelligence.fr3k.integrations.openrouter.OpenRouterProvider.DEFAULT_MODEL
                                status = "free model selected"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
                        ) { Text("USE FREE", fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                        Button(
                            onClick = {
                                scope.launch {
                                    status = "refreshing…"
                                    freeModels = provider.refreshFreeModels().getOrDefault(emptyList())
                                    status = "${freeModels.size} free models cached"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
                        ) { Text("REFRESH", fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                    }
                    if (freeModels.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        freeModels.take(6).forEach { m ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Fr3kPalette.Surface)
                                    .clickable {
                                        provider.setModel(m.id)
                                        app.settings.update { it.copy(openrouterModel = m.id) }
                                        modelInput = m.id
                                        status = "model selected"
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(m.id, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }
                        }
                    }
                }
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
                    Text(ctx.summary(), color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
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
private fun HermesTokenEditor(app: Fr3kApplication, settings: com.mcpintelligence.fr3k.core.AppSettings.Settings) {
    val scope = rememberCoroutineScope()
    var tokenInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val stored = remember(settings.hermesAuthTokenKey) {
        app.secureStore.get(settings.hermesAuthTokenKey)
    }
    Column {
        Text(
            text = "auth token: ${if (stored != null) "stored (encrypted) — ${maskKey(stored)}" else "not set"}",
            color = if (stored != null) Fr3kPalette.Ok else Fr3kPalette.Warn,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        androidx.compose.material3.OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            textStyle = androidx.compose.ui.text.TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            placeholder = { Text("bearer token…", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = {
                    val trimmed = tokenInput.trim()
                    if (trimmed.isNotEmpty()) {
                        app.secureStore.put(settings.hermesAuthTokenKey, trimmed)
                        tokenInput = ""
                        status = "token saved (encrypted)"
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
            ) { Text("SAVE TOKEN", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            Button(
                onClick = { app.secureStore.remove(settings.hermesAuthTokenKey); tokenInput = ""; status = "token removed" },
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
            ) { Text("REMOVE", fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
            Button(
                onClick = {
                    scope.launch {
                        status = "testing…"
                        val result = withContext(Dispatchers.IO) {
                            hermesReachability(
                                endpoint = app.settings.settings.value.hermesEndpoint,
                                token = app.secureStore.get(settings.hermesAuthTokenKey),
                            )
                        }
                        status = if (result.startsWith("reachable")) "ONLINE — $result" else result
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
            ) { Text("TEST", fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
        }
        status?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = if (it.startsWith("ONLINE") || it.startsWith("reachable") || it == "token saved") Fr3kPalette.Ok else Fr3kPalette.Err, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}

@Composable
private fun BlackwaveSettingsEditor(app: Fr3kApplication, settings: com.mcpintelligence.fr3k.core.AppSettings.Settings) {
    val scope = rememberCoroutineScope()
    var endpoint by remember(settings.blackwaveEndpoint) { mutableStateOf(settings.blackwaveEndpoint) }
    var clientId by remember(settings.blackwaveClientId) { mutableStateOf(settings.blackwaveClientId) }
    var credentialInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val storedCred = remember(settings.blackwaveCredentialKey) {
        app.secureStore.get(settings.blackwaveCredentialKey)
    }
    Column {
        Text("endpoint (envelope POST root):", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        androidx.compose.material3.OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            textStyle = androidx.compose.ui.text.TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("client id:", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        androidx.compose.material3.OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            textStyle = androidx.compose.ui.text.TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "credential: ${if (storedCred != null) "stored (encrypted) — ${maskKey(storedCred)}" else "not set"}",
            color = if (storedCred != null) Fr3kPalette.Ok else Fr3kPalette.Warn,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        androidx.compose.material3.OutlinedTextField(
            value = credentialInput,
            onValueChange = { credentialInput = it },
            textStyle = androidx.compose.ui.text.TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            placeholder = { Text("bwcm_…", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = {
                    val ep = endpoint.trim().ifBlank { AppSettings.DEFAULT_BLACKWAVE_ENDPOINT }
                    val cid = clientId.trim().ifBlank { AppSettings.DEFAULT_BLACKWAVE_CLIENT_ID }
                    app.settings.update { it.copy(blackwaveEndpoint = ep, blackwaveClientId = cid) }
                    val cred = credentialInput.trim()
                    if (cred.isNotEmpty()) {
                        app.secureStore.put(settings.blackwaveCredentialKey, cred)
                        credentialInput = ""
                    }
                    status = "saved"
                },
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
            ) { Text("SAVE", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            Button(
                onClick = { app.secureStore.remove(settings.blackwaveCredentialKey); credentialInput = ""; status = "credential removed" },
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
            ) { Text("REMOVE CRED", fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
            Button(
                onClick = {
                    scope.launch {
                        status = "testing…"
                        val result = withContext(Dispatchers.IO) {
                            blackwaveReachability(
                                endpoint = endpoint.trim().ifBlank { AppSettings.DEFAULT_BLACKWAVE_ENDPOINT },
                                credential = app.secureStore.get(settings.blackwaveCredentialKey),
                                clientId = clientId.trim().ifBlank { AppSettings.DEFAULT_BLACKWAVE_CLIENT_ID },
                            )
                        }
                        status = if (result.startsWith("reachable")) "ONLINE — $result" else result
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
            ) { Text("TEST", fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
        }
        status?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = if (it.startsWith("ONLINE") || it.startsWith("reachable") || it == "saved" || it == "credential removed") Fr3kPalette.Ok else Fr3kPalette.Err, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}

/**
 * Lightweight reachability probe for the Hermes envelope endpoint. Returns a
 * human-readable result; does not leak the token in the response text.
 */
private fun hermesReachability(endpoint: String, token: String?): String {
    return try {
        val url = com.mcpintelligence.fr3k.transport.HttpsTransport.buildEnvelopeUrl(endpoint)
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 8_000
            requestMethod = "GET"
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            instanceFollowRedirects = false
        }
        val code = conn.responseCode
        conn.disconnect()
        // Any HTTP response (even 404/405) means the server is reachable.
        "reachable — HTTP $code"
    } catch (e: java.net.ConnectException) {
        "unreachable — connection refused"
    } catch (e: Exception) {
        "unreachable — ${e.message}"
    }
}

/**
 * Reachability probe for the BLACKWAVE mobile gateway. A successful read on
 * the health route means the endpoint + credential + client id are wired.
 */
private suspend fun blackwaveReachability(endpoint: String, credential: String?, clientId: String): String {
    return try {
        val client = BlackwaveBridgeClient(
            endpointProvider = { endpoint },
            credentialProvider = { credential },
            clientIdProvider = { clientId },
        )
        val available = client.isAvailable()
        if (!available) "unreachable — health check failed (check endpoint / TLS) "
        else {
            val role = client.fetchRole()
            if (role.isSuccess) "reachable — role=${role.getOrNull()?.role_id ?: "?"}"
            else "reachable (health ok) — role not served: ${role.exceptionOrNull()?.message?.take(80)}"
        }
    } catch (e: java.net.ConnectException) {
        "unreachable — connection refused"
    } catch (e: Exception) {
        "unreachable — ${e.message}"
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Switch(checked = value, onCheckedChange = onChange)
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
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = (if (active) "● " else "○ ") + label,
            color = if (active) Fr3kPalette.Bg else Fr3kPalette.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
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