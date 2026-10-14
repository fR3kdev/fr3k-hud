package com.mcpintelligence.fr3k.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.protocol.DeviceStatus

/** Status dot — used everywhere we render an "online / offline / degraded" signal. */
@Composable
fun StatusDot(
    status: DeviceStatus,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val color = when (status) {
        DeviceStatus.ONLINE -> Fr3kPalette.Ok
        DeviceStatus.DEGRADED -> Fr3kPalette.Warn
        DeviceStatus.OFFLINE -> Fr3kPalette.Err
        DeviceStatus.UNKNOWN -> Fr3kPalette.TextDim
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}

@Composable
fun Fr3kPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    var explain by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .border(1.dp, Fr3kPalette.Border, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp)),
        color = Fr3kPalette.Surface,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Fr3kPalette.Accent,
                    )
                    // The clickable node itself carries the name: applying semantics to TextButton leaves the
                    // outer clickable node unnamed in the accessibility tree.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .heightIn(min = 48.dp)
                            .clickable { explain = !explain }
                            .semantics { contentDescription = "Why is $title here?" }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(if (explain) "−" else "?", color = Fr3kPalette.Accent)
                    }
                }
                if (explain) Text(panelExplanation(title), style = MaterialTheme.typography.bodyMedium, color = Fr3kPalette.TextDim)
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Fr3kPalette.TextDim)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = Fr3kPalette.Text)
    }
}

@Composable
fun Fr3kBadge(text: String, color: Color = Fr3kPalette.Accent, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .border(1.dp, color, RoundedCornerShape(4.dp)),
        color = Color.Transparent,
    ) {
        Text(
            text = text.uppercase(),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun panelExplanation(title: String): String {
    val name = title.lowercase()
    return when {
        "bridge" in name -> "The accessibility service reads visible app UI and performs only reviewed, fingerprinted actions. Enable it in Android Accessibility."
        "shell" in name || "root" in name -> "This tier is granted only when HUD's own privileged service reports its actual UID. Use the bootstrap in App control; installation alone does not grant access."
        "mcp" in name || "intelligence" in name -> "The selected AI provider answers chat. The separate local MCP server exposes registered capabilities and guides to an agent you trust."
        "floating" in name || "hud" == name -> "Overlay permission enables independent windows. Drag the title, resize the corner and close each window separately. Outside taps reach the app underneath."
        "cannot" in name || "limits" in name -> "These boundaries are part of the supported capability contract. The owned fixture does not imply support for arbitrary apps."
        "undo" in name || "journal" in name || "evidence" in name -> "Observed outcomes and recorded inverses show what changed. No state change means there is no automatic undo operation."
        "provider" in name || "router" in name || "hermes" in name -> "Configure the endpoint and key explicitly, then test the observed connection. Remote AI uses your chosen context and provider subscription."
        "permission" in name || "consent" in name -> "Android grants permissions only after your choice in its consent UI. Revoke grants in Android Settings when no longer needed."
        "context" in name -> "Context is information shared with the selected command or provider. Review included fields before sending; sent information cannot be recalled."
        "fleet" in name || "device" in name -> "Only observed compatible devices can receive handoffs. An empty list means no peer has been discovered; it is not simulated hardware."
        "rules" in name || "logs" in name -> "Rules describe trigger-to-action behaviour. Logs describe the observed outcome, including skipped and failed runs."
        else -> "This panel groups related state or actions. Check requirements before running. APP MODS explains capability steps, limits and undo; Journal records app-control outcomes."
    }
}
