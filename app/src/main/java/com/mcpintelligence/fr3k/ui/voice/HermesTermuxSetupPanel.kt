package com.mcpintelligence.fr3k.ui.voice

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.setup.HermesSetupPlan
import com.mcpintelligence.fr3k.setup.HermesTermuxSetup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Standalone optional offer; generic Send to agent never depends on this panel. No credential inputs. */
@Composable
fun HermesTermuxSetupPanel(
    provider: String,
    model: String,
    modifier: Modifier = Modifier,
    onVerifiedChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val helper = remember(context, provider, model) {
        HermesTermuxSetup(context, Fr3kApplication.get().termuxBridge)
    }
    val scope = rememberCoroutineScope()
    var consent by remember(provider, model) { mutableStateOf(false) }
    var busy by remember(provider, model) { mutableStateOf(false) }
    var status by remember(provider, model) { mutableStateOf(HermesTermuxSetup.Status("Not verified. Optional: your normal agent works without Hermes.")) }
    DisposableEffect(helper) {
        onVerifiedChange(false)
        onDispose { onVerifiedChange(false) }
    }
    fun run(action: suspend () -> HermesTermuxSetup.Status) {
        if (busy) return
        busy = true
        status = HermesTermuxSetup.Status("Waiting for a real Termux result…")
        onVerifiedChange(false)
        scope.launch {
            try {
                status = action()
                onVerifiedChange(status.verified)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = HermesTermuxSetup.Status("Setup could not be confirmed. Check Termux permissions, model selection and the official docs.")
                onVerifiedChange(false)
            } finally { busy = false }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
    ) {
    Column(modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("OPTIONAL · HERMES IN TERMUX")
        Text(HermesSetupPlan.UPSTREAM_WARNING)
        Text("Selected: $provider / $model. Requires standard Termux on arm64, Run commands permission and allow-external-apps=true. The app does not silently grant permissions.")
        Text("The current bridge cannot securely transfer your saved API key. It will never copy it to clipboard, command arguments, shell history or shared files. Enter it directly in Hermes's masked setup prompt. A separate FR3K phone profile is used; existing Hermes defaults are not changed.")
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HermesSetupPlan.DOCS))) }
        }) { Text("OFFICIAL INSTALL / TROUBLESHOOTING") }
        Row {
            Checkbox(checked = consent, enabled = !busy, onCheckedChange = {
                consent = it
                status = HermesTermuxSetup.Status("Not verified. Consent changed; run verification again.")
                onVerifiedChange(false)
            })
            Text("I consent to the optional signed APT install, dedicated profile configuration and a live provider test (may use API credit).", modifier = Modifier.padding(top = 8.dp))
        }
        val enabled = consent && !busy && helper.available() && helper.supported()
        if (!helper.available()) Text("Termux bridge unavailable. Install/open Termux and grant Run commands in Android app settings; then reopen this panel.")
        if (!helper.supported()) Text("Unsupported architecture: official Hermes Termux packages require arm64.")
        Button(enabled = enabled, onClick = { run { helper.install(consent) } }) { Text("1 · INSTALL SIGNED PACKAGE (OPTIONAL)") }
        Button(enabled = enabled, onClick = { run { helper.configure(provider, model, consent) } }) { Text("2 · CONFIGURE SELECTED PROVIDER / MODEL") }
        Button(enabled = enabled, onClick = {
            status = helper.openWizard(consent)
            onVerifiedChange(false)
        }) { Text("3 · ENTER KEY IN TERMUX WIZARD") }
        Text("In the wizard choose the selected provider/model. For Groq choose a custom OpenAI-compatible endpoint: https://api.groq.com/openai/v1. If the wizard changes your model, repeat step 2 before verifying.")
        Button(enabled = enabled, onClick = { run { helper.verify(provider, model, consent) } }) { Text("4 · VERIFY LIVE HERMES RESPONSE") }
        Text(status.message)
        // Never offer a Hermes-specific launch based on package presence or remembered completion.
        if (status.verified && consent) {
            Button(onClick = {
                if (!helper.openAgent(provider, model)) {
                    status = HermesTermuxSetup.Status("Termux launch was not confirmed. Verify again.")
                    onVerifiedChange(false)
                }
            }) { Text("OPEN VERIFIED HERMES") }
        }
    }
    }
}
