package com.mcpintelligence.fr3k.ui.settings

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.ConsentLevel
import com.mcpintelligence.fr3k.hud.OrbAppearance
import com.mcpintelligence.fr3k.ui.Fr3kBadge
import com.mcpintelligence.fr3k.ui.Fr3kButton
import com.mcpintelligence.fr3k.ui.Fr3kDimens
import com.mcpintelligence.fr3k.ui.Fr3kGhostButton
import com.mcpintelligence.fr3k.ui.Fr3kGradientText
import com.mcpintelligence.fr3k.ui.Fr3kOutlineButton
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kPanel
import com.mcpintelligence.fr3k.ui.Fr3kRowItem
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import kotlinx.coroutines.launch

/**
 * Settings screen. Live surface over [AppSettings].
 *
 * Controls:
 *   - HUD enabled
 *   - HUD edge margin
 *   - Orb appearance (image/GIF picker, size slider, reset)
 *   - Consent profile (LOCAL_ONLY / PRIVATE / NORMAL / RESEARCH)
 *   - Hermes endpoint
 *   - OpenRouter key/model
 *   - Telemetry enabled
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Fr3kTheme { SettingsScreen(onClose = { finish() }) } }
    }
}

private val Fr3kFieldShape = RoundedCornerShape(Fr3kDimens.RadiusRow)

@Composable
private fun fr3kFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Fr3kPalette.Accent,
    unfocusedBorderColor = Fr3kPalette.Border,
    focusedTextColor = Fr3kPalette.Text,
    unfocusedTextColor = Fr3kPalette.Text,
    cursorColor = Fr3kPalette.Ok,
    focusedContainerColor = Fr3kPalette.SurfaceLow,
    unfocusedContainerColor = Fr3kPalette.SurfaceLow,
)

@Composable
private fun SettingsScreen(onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    val context = androidx.compose.ui.platform.LocalContext.current
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
                .padding(Fr3kDimens.SpacingLg),
            verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingMd),
        ) {
            Column {
                Fr3kGradientText(text = "SETTINGS", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "system · orb · providers · consent",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = Fr3kPalette.TextDim,
                )
            }

            Fr3kPanel(title = "HUD") {
                Column {
                    Toggle("HUD overlay enabled", settings.hudEnabled) { v ->
                        app.settings.update { it.copy(hudEnabled = v) }
                    }
                    Spacer(Modifier.height(Fr3kDimens.SpacingSm))
                    Text("edge margin: ${settings.hudEdgeMarginDp} dp", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Slider(
                        value = settings.hudEdgeMarginDp.toFloat(),
                        onValueChange = { v -> app.settings.update { it.copy(hudEdgeMarginDp = v.toInt()) } },
                        valueRange = 0f..48f,
                        steps = 16,
                        colors = fr3kSliderColors(),
                    )
                }
            }

            Fr3kPanel(title = "orb appearance", subtitle = "custom button") {
                val orbStore = remember { com.mcpintelligence.fr3k.hud.OrbAppearanceStore(context) }
                var orbApp by remember { mutableStateOf(orbStore.read()) }
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                        val isGif = (context.contentResolver.getType(uri) ?: "").contains("gif") ||
                            uri.toString().lowercase().endsWith(".gif")
                        orbApp = orbApp.copy(
                            mode = if (isGif) OrbAppearance.Mode.GIF else OrbAppearance.Mode.IMAGE,
                            imageUri = uri.toString(),
                        )
                        orbStore.write(orbApp)
                        refreshOrb(context)
                    }
                }
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Fr3kBadge(
                            text = when (orbApp.mode) {
                                OrbAppearance.Mode.BUILTIN -> "built-in glyph"
                                OrbAppearance.Mode.IMAGE -> "custom image"
                                OrbAppearance.Mode.GIF -> "animated gif"
                            },
                            color = if (orbApp.mode == OrbAppearance.Mode.BUILTIN) Fr3kPalette.TextDim else Fr3kPalette.Ok,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "size ${orbApp.clampedSizeDp} dp",
                            color = Fr3kPalette.TextDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        )
                    }
                    Spacer(Modifier.height(Fr3kDimens.SpacingMd))
                    Row(horizontalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
                        Fr3kButton(
                            text = "PICK IMAGE / GIF",
                            onClick = { picker.launch(arrayOf("image/*")) },
                            modifier = Modifier.weight(1f),
                        )
                        Fr3kGhostButton(
                            text = "RESET",
                            onClick = {
                                orbStore.resetImage()
                                orbApp = orbStore.read()
                                refreshOrb(context)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(Fr3kDimens.SpacingSm))
                    Slider(
                        value = orbApp.clampedSizeDp.toFloat(),
                        onValueChange = { v -> orbApp = orbApp.copy(sizeDp = v.toInt()) },
                        onValueChangeFinished = {
                            orbStore.write(orbApp)
                            refreshOrb(context)
                        },
                        valueRange = OrbAppearance.MIN_SIZE_DP.toFloat()..OrbAppearance.MAX_SIZE_DP.toFloat(),
                        steps = 25,
                        colors = fr3kSliderColors(),
                    )
                }
            }

            Fr3kPanel(title = "consent profile") {
                Column(verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
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

            Fr3kPanel(title = "Hermes") {
                Column(verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
                    Text("endpoint:", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        textStyle = TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        shape = Fr3kFieldShape,
                        colors = fr3kFieldColors(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Fr3kButton(
                        text = "SAVE ENDPOINT",
                        onClick = { app.settings.update { it.copy(hermesEndpoint = endpoint) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "auth token: ${if (app.secureStore.get(settings.hermesAuthTokenKey) != null) "stored (encrypted)" else "not set"}",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
            }

            Fr3kPanel(title = "OpenRouter") {
                val scope = rememberCoroutineScope()
                val provider = app.openRouterProvider
                val storedKey = app.secureStore.get(settings.openrouterApiKeyKey)
                var keyInput by remember { mutableStateOf("") }
                var modelInput by remember(provider.selectedModel()) { mutableStateOf(provider.selectedModel()) }
                var status by remember { mutableStateOf<String?>(null) }
                var freeModels by remember { mutableStateOf<List<com.mcpintelligence.fr3k.integrations.openrouter.OpenRouterModel>>(emptyList()) }
                Column(verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Fr3kBadge(
                            text = if (storedKey != null) "key stored" else "key missing",
                            color = if (storedKey != null) Fr3kPalette.Ok else Fr3kPalette.Warn,
                        )
                        Text(
                            text = if (storedKey != null) maskKey(storedKey) else "add an sk-or-… key",
                            color = Fr3kPalette.TextDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        textStyle = TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        placeholder = { Text("sk-or-…", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        shape = Fr3kFieldShape,
                        colors = fr3kFieldColors(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
                        Fr3kButton(
                            text = "SAVE KEY",
                            onClick = {
                                val trimmed = keyInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    app.secureStore.put(settings.openrouterApiKeyKey, trimmed)
                                    keyInput = ""
                                    status = "key saved"
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Fr3kGhostButton(
                            text = "REMOVE",
                            onClick = { app.secureStore.remove(settings.openrouterApiKeyKey); status = "key removed" },
                            modifier = Modifier.weight(1f),
                        )
                        Fr3kOutlineButton(
                            text = "VALIDATE",
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
                            modifier = Modifier.weight(1f),
                        )
                    }
                    status?.let {
                        Text(it, color = if (it.startsWith("ONLINE") || it == "key saved") Fr3kPalette.Ok else Fr3kPalette.Err, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                    Text("model: ${provider.selectedModel()}", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        textStyle = TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        shape = Fr3kFieldShape,
                        colors = fr3kFieldColors(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
                        Fr3kButton(
                            text = "USE MODEL",
                            onClick = {
                                val m = modelInput.trim()
                                if (m.isNotEmpty()) {
                                    provider.setModel(m)
                                    app.settings.update { it.copy(openrouterModel = m) }
                                    status = "model set"
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Fr3kGhostButton(
                            text = "USE FREE",
                            onClick = {
                                provider.setModel(com.mcpintelligence.fr3k.integrations.openrouter.OpenRouterProvider.DEFAULT_MODEL)
                                app.settings.update { it.copy(openrouterModel = com.mcpintelligence.fr3k.integrations.openrouter.OpenRouterProvider.DEFAULT_MODEL) }
                                modelInput = com.mcpintelligence.fr3k.integrations.openrouter.OpenRouterProvider.DEFAULT_MODEL
                                status = "free model selected"
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Fr3kOutlineButton(
                            text = "REFRESH",
                            onClick = {
                                scope.launch {
                                    status = "refreshing…"
                                    freeModels = provider.refreshFreeModels().getOrDefault(emptyList())
                                    status = "${freeModels.size} free models cached"
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (freeModels.isNotEmpty()) {
                        freeModels.take(6).forEach { m ->
                            Fr3kRowItem(
                                label = m.id,
                                accent = Fr3kPalette.Text,
                                onClick = {
                                    provider.setModel(m.id)
                                    app.settings.update { it.copy(openrouterModel = m.id) }
                                    modelInput = m.id
                                    status = "model selected"
                                },
                                trailing = { Text("›", color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
                            )
                        }
                    }
                }
            }

            Fr3kPanel(title = "telemetry") {
                Toggle("telemetry enabled (crash-free only)", telemetry) { v -> telemetry = v; app.settings.update { it.copy(telemetryEnabled = v) } }
            }

            Fr3kPanel(title = "current context") {
                Column(verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
                    Text(ctx.summary(), color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Fr3kGhostButton(
                        text = "CLEAR CONTEXT",
                        onClick = { contextEngine.clear() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Fr3kOutlineButton(
                text = "CLOSE",
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
                accent = Fr3kPalette.TextDim,
            )
        }
    }
}

@Composable
private fun fr3kSliderColors() = SliderDefaults.colors(
    thumbColor = Fr3kPalette.Accent,
    activeTrackColor = Fr3kPalette.Accent,
    inactiveTrackColor = Fr3kPalette.Border,
    activeTickColor = Fr3kPalette.Bg,
    inactiveTickColor = Fr3kPalette.SurfaceHigh,
)

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val trackColor by animateColorAsState(
        if (value) Fr3kPalette.Ok else Fr3kPalette.SurfaceHigh,
        tween(220),
        label = "toggleTrack",
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = trackColor,
                checkedThumbColor = Fr3kPalette.Bg,
                checkedBorderColor = trackColor,
                uncheckedTrackColor = Fr3kPalette.Surface,
                uncheckedThumbColor = Fr3kPalette.TextDim,
                uncheckedBorderColor = Fr3kPalette.Border,
            ),
        )
    }
}

@Composable
private fun RadioRow(active: Boolean, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Fr3kDimens.RadiusRow)
    val bg by animateColorAsState(
        if (active) Fr3kPalette.AccentDim.copy(alpha = 0.55f) else Fr3kPalette.SurfaceLow,
        tween(200),
        label = "radioBg",
    )
    val brd by animateColorAsState(
        if (active) Fr3kPalette.Accent else Fr3kPalette.Border.copy(alpha = 0.45f),
        tween(200),
        label = "radioBrd",
    )
    val scale by animateFloatAsState(if (active) 1.015f else 1f, tween(180), label = "radioScale")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, brd, shape)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = (if (active) "◉ " else "○ ") + label,
            color = if (active) Fr3kPalette.Text else Fr3kPalette.TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
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

private fun refreshOrb(context: Context) {
    runCatching {
        context.startService(
            android.content.Intent(context, com.mcpintelligence.fr3k.hud.HudOverlayService::class.java)
                .setAction(com.mcpintelligence.fr3k.hud.HudOverlayService.ACTION_REFRESH_APPEARANCE),
        )
    }
}
