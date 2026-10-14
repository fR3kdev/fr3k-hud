package com.mcpintelligence.fr3k.ui

import android.content.Intent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/** Persistent in-context help. Expands in place without capturing another app's input. */
@Composable
fun FeatureGuide(feature: String) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val help = when (feature) {
        "Share actions" -> "Review the incoming payload before choosing an action. Text transformations use the selected AI provider and may incur subscription costs. Local-only consent prevents AI egress. Image analysis is not wired here; a file URI is a reference, not extracted image content. Close dismisses this sheet without sending. Sent content cannot be recalled."
        "Settings" -> "Choose a consent profile and save provider settings explicitly. OpenCode Go uses its subscription endpoint and a tool-capable model. TEST reports the observed provider health. REMOVE deletes the stored key; saving a key changes routing according to your preference. Remote AI requests use the selected shared context and may incur provider costs. Clear context does not undo executed actions."
        "Diagnostics" -> "RUN FULL DIAGNOSTIC checks permissions, service state and provider availability. PASS, WARN and FAIL describe observed results. GRANT opens Android consent; it does not grant permission automatically. EXPORT shares the report after you choose a destination. Diagnostics do not change target apps."
        "Automations" -> "Rules show trigger → action, enabled state, debounce and fire count. The log shows actual outcomes. This screen inspects the starter rules; use Workflows on the Command Deck for reviewed capability execution. Remote actions need their provider or peer. Reading rules needs no undo."
        "Clipboard" -> "Clipboard text is read once when you open this surface. Select an action to clean a URL or send explicitly chosen text onward. No background monitoring. Review the resulting text before sharing. Copy the original first if you need to restore it."
        "Screenshot" -> "Capture requires Android's screen-sharing consent. Save stores actual image bytes locally; Share opens Android's chooser. Capture does not remove secure-content restrictions. Delete a saved image to undo storage; a shared image cannot be recalled by HUD."
        "Palette" -> "Type to search registered commands. Each command shows its required capabilities. Run only after checking the target and context. A command may open a system consent screen or require an AI provider or physical peer. Review app-changing actions through APP MODS and Workflows; check Journal for their real inverse."
        "Ask" -> "Choose exactly which context fields to include, enter your question, then send. Only enabled context fields are sent to the selected AI provider. Network and provider configuration are required; costs follow your provider subscription. Sending cannot be undone. Keep credentials out of the prompt."
        "Handoff" -> "Select a discovered device and inspect its payload representation. Direct peer delivery is not wired here; Share reviewed payload opens Android’s chooser with the text. Verify the actual recipient there. No peers means there is no peer-specific preview. Shared content cannot be recalled."
        "Developer" -> "Inspect observed foreground app metadata and copy a report. Usage access is required for foreground detection; unavailable values are not fabricated. Copy overwrites the clipboard, so preserve anything needed first. No target app is modified."
        "BLACKWAVE" -> "Configure the companion bridge, test its observed health, and inspect discovered fleet devices. Needs your companion and real hardware. A successful local UI check does not prove a radio or device operation. Review payload and device identity before sending; external effects may have no undo."
        "Setup" -> "Grant only the permissions you want in Android's real settings. Accessibility enables the Bridge plane; overlay permission enables floating windows. Shell/root are optional and require the bootstrap in App control. Configure a provider in Settings. Choose an installed app in App control to inspect, map or patch with a compatible module. You can revisit this walkthrough or revoke permissions in Android settings."
        else -> "Check this panel's requirements, choose the exact target, review the outcome and read its inverse in Journal. APP MODS contains the capability walkthroughs, limits and undo instructions."
    }
    Column(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide $feature guide" else "How to use $feature · ?") }
        if (expanded) {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) { DeckText(help) }
            OutlinedButton(onClick = { context.startActivity(Intent(context, com.mcpintelligence.fr3k.ui.mods.ModsActivity::class.java)) }) { Text("Capability walkthroughs →") }
        }
    }
}
