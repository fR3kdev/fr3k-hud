package com.mcpintelligence.fr3k.ui.clipboard

import android.content.ClipDescription
import android.content.ClipboardManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.SmartClipboardCommand
import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.ConsentLevel
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kTheme

/**
 * Smart clipboard (§29). Reads the current clipboard ONCE on entry
 * (never continuously), classifies the content, and proposes actions.
 * The user explicitly invokes this — no background clipboard reading.
 */
class SmartClipboardActivity : ComponentActivity() {
    private var clipboardText by mutableStateOf<String?>(null)
    private var readOnce = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Fr3kTheme { SmartClipboardScreen(initialText = clipboardText, onClose = { finish() }) } }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || readOnce) return
        readOnce = true
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        clipboardText = if (clip != null && clip.itemCount > 0 &&
            (clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)))
            clip.getItemAt(0).coerceToText(this)?.toString() else null
    }
}

@Composable
private fun SmartClipboardScreen(initialText: String?, onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    val capabilities by app.capabilityRegistry.snapshot.collectAsState()

    var text by remember(initialText) { mutableStateOf(initialText ?: "") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialText) {
        if (initialText != null) {
            app.fr3kCore.contextEngine.update { it.copy(clipboardText = initialText) }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Fr3kPalette.Bg)) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState()).padding(16.dp)) {
            com.mcpintelligence.fr3k.ui.FeatureGuide("Clipboard")

            Text(
                text = "SMART CLIPBOARD",
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "clipboard content") {
                if (text.isBlank()) {
                    Text(
                        text = "no text on the clipboard",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    Text(
                        text = text.take(500),
                        color = Fr3kPalette.Text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "suggested actions") {
                Column {
                    listOf(
                        "classify" to "context.clipboard",
                        "explain" to "share.text.explain",
                        "rewrite" to "share.text.rewrite",
                        "translate" to "share.text.translate",
                        "summarise" to "share.text.summarise",
                        "send to mesh" to "share.mesh.send",
                    ).forEach { (label, id) ->
                        ActionRow(label.uppercase()) {
                            if (!busy) {
                                busy = true; result = "Running $label…"
                                scope.launch { result = withContext(Dispatchers.IO) { runCommand(app, id, mapOf("text" to text)) }; busy = false }
                            }
                        }
                    }
                }
            }

            if (result != null) {
                Spacer(Modifier.height(12.dp))
                com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "result") {
                    Text(
                        text = result!!,
                        color = if (result!!.startsWith("✗")) Fr3kPalette.Err else Fr3kPalette.Text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Fr3kPalette.Surface,
                    contentColor = Fr3kPalette.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("DISMISS", fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private suspend fun runCommand(app: Fr3kApplication, id: String, args: Map<String, String>): String {
    val cmd = app.fr3kCore.commandRegistry.get(id) ?: return "command not registered"
    val caps = app.fr3kCore.currentCapabilities()
    val ok = cmd.requiredCapabilities.all { it in caps }
    if (!ok) return "missing capability: ${cmd.requiredCapabilities - caps}"
    val ctx = Fr3kContext(deviceId = app.identity.deviceId, consentLevel = app.settings.settings.value.consentProfile, enabledCapabilities = caps)
    return when (val r = com.mcpintelligence.fr3k.core.executeCommand(cmd, ctx, args)) {
        is CommandResult.Ok -> "✓ ${r.message}"
        is CommandResult.Failed -> "✗ ${r.reason}"
        is CommandResult.Cancelled -> "– ${r.reason}"
        is CommandResult.NeedsConfirmation -> "? ${r.summary}"
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Fr3kPalette.Surface)
            .heightIn(min = 48.dp).clickable { onClick() }
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = "›",
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = label,
                color = Fr3kPalette.Text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
}