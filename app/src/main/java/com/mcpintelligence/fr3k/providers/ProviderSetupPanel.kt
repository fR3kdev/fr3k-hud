package com.mcpintelligence.fr3k.providers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.integrations.providers.*
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import kotlinx.coroutines.launch

/** Insert in any scrollable settings surface. No raw credential is saveable or rendered. */
@Composable
fun ProviderSetupPanel(
    service: ProviderSetupService,
    modifier: Modifier = Modifier,
    onValidated: () -> Unit = {},
    onSaved: () -> Unit = {},
) {
    val settings by service.state.collectAsState()
    var provider by remember { mutableStateOf(AgentProvider.GROQ) }
    var key by remember(provider) { mutableStateOf("") }
    var catalog by remember(provider) { mutableStateOf<ProviderValidation?>(null) }
    var model by remember(provider) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember(provider) { mutableStateOf("") }
    var expanded by remember(provider) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI provider setup", style = MaterialTheme.typography.titleMedium)
        Text("Selected: ${settings.activeAgentProvider} / ${service.selectedModel(settings)}")
        AgentProvider.entries.forEach { option ->
            OutlinedButton(onClick = { provider = option }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text((if (provider == option) "● " else "○ ") + option.title)
            }
        }
        Text(provider.guidance)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { uri.openUri(provider.signupUrl) }) { Text("Sign up / plan") }
            TextButton(onClick = { uri.openUri(provider.keyUrl) }) { Text("Create API key") }
        }
        OutlinedTextField(value = key, onValueChange = { key = it; catalog = null; model = null; status = "" },
            enabled = !busy, label = { Text("API key (masked)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = key.isNotBlank() && !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val result = service.validate(provider, key.trim())
                    catalog = result
                    // Never leave the operator with no model chosen: a default that is not advertised
                    // would fail the save with a bare provider error.
                    model = result.defaultModel
                        ?: result.models.firstOrNull { !it.free }?.id
                        ?: result.models.firstOrNull()?.id
                    status = result.message
                    onValidated()
                } finally { busy = false }
            }
        }) { Text(if (busy) "Checking…" else "Validate key and list models") }
        if (service.hasSavedKey(provider)) {
            Text(
                "A ${provider.title} key is already saved on this device. Reusing it verifies the stored key with one small request and makes it active; nothing is displayed.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val result = service.activateWithStoredKey(provider)
                        status = result.message
                        if (result.inferenceVerified) onSaved()
                    } finally { busy = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("USE SAVED KEY") }
        }
        if (catalog?.status == ValidationStatus.VALID) {
            Text("Requested: ${if (provider == AgentProvider.GROQ) settings.requestedGroqModel else settings.requestedDeepseekModel}. Advertised ID: ${provider.requestedModel}")
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = !busy) { Text(model ?: "Choose an available model") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                    catalog?.models?.forEach { item ->
                        DropdownMenuItem(text = { Text(item.id + if (item.free) " (free)" else "") }, onClick = { model = item.id; expanded = false })
                    }
                }
            }
            Text("Verify & save sends a minimal inference request. Provider charges and limits may apply. A model listing alone does not prove chat access.")
            Button(enabled = !busy && model != null, onClick = {
                val selection = model ?: return@Button
                val listed = catalog ?: return@Button
                busy = true
                scope.launch {
                    try {
                        val result = service.saveSelection(provider, key.trim(), selection, listed)
                        status = result.message
                        if (result.inferenceVerified) { key = ""; catalog = null; model = null; onSaved() }
                    } finally { busy = false }
                }
            }) { Text("Verify inference & save selection") }
        }
        if (status.isNotBlank()) Text(status)
    }
    }
}
