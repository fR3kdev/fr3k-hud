package com.mcpintelligence.fr3k.hud.quickhud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as GraphicsColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.integrations.IntegrationState
import com.mcpintelligence.fr3k.integrations.termux.TermuxHealth
import com.mcpintelligence.fr3k.protocol.DeviceStatus as ProtoDeviceStatus
import com.mcpintelligence.fr3k.ui.Fr3kBadge
import com.mcpintelligence.fr3k.ui.Fr3kDimens
import com.mcpintelligence.fr3k.ui.Fr3kDivider
import com.mcpintelligence.fr3k.ui.Fr3kGradientText
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kPanel
import com.mcpintelligence.fr3k.ui.Fr3kRowItem
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import com.mcpintelligence.fr3k.ui.StatusDot
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import com.mcpintelligence.fr3k.ui.palette.CommandPaletteActivity

/**
 * The quick HUD panel (§8). A compact overlay launched from the HUD orb (tap),
 * Quick Settings tile, or notification action. Shows:
 *
 *   - device id + fingerprint
 *   - profile-aware adapter status (Hermes, MeshCore, Meshtastic, GPS, Termux)
 *   - command palette entry
 *   - Ask About This
 *   - close
 *
 * Renders from current capability registry (no hardcoded service list — V2
 * mesh plugins will light up automatically when added).
 */
class QuickHudActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Fr3kTheme { QuickHudScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun QuickHudScreen(onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    val caps by app.capabilityRegistry.snapshot.collectAsState()
    val profile by remember { mutableStateOf(app.fr3kCore.profileForCurrent()) }
    val hudCtx = LocalContext.current
    // §4: Termux is probed for REAL (package → permission → echo-ok round-trip),
    // never inferred from capability strings. Green only when operational.
    var termuxState by remember { mutableStateOf(TermuxHealth.lastKnown()) }
    LaunchedEffect(app) {
        termuxState = TermuxHealth.probe(hudCtx.applicationContext, app.termuxBridge)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(GraphicsColor.Black.copy(alpha = 0.72f), GraphicsColor.Black.copy(alpha = 0.48f)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier.padding(Fr3kDimens.SpacingLg),
            verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingMd),
        ) {
            Fr3kPanel(modifier = Modifier.fillMaxWidth(), title = "FR3K", subtitle = "profile: $profile") {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Fr3kGradientText(
                            text = app.fr3kCore.identity.deviceId.take(20),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Fr3kBadge(
                            text = if (caps.isNotEmpty()) "${caps.size} caps" else "0 caps",
                            color = if (caps.isNotEmpty()) Fr3kPalette.Ok else Fr3kPalette.TextDim,
                        )
                    }
                    Spacer(Modifier.height(Fr3kDimens.SpacingSm))
                    Fr3kDivider()
                    Spacer(Modifier.height(Fr3kDimens.SpacingSm))
                    AdapterRows(caps = caps.keys, termuxState = termuxState)
                }
            }

            Fr3kPanel(modifier = Modifier.fillMaxWidth(), title = "ACTIONS") {
                val ctx = LocalContext.current
                Column(verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
                    QuickAction("OPEN COMMAND PALETTE") {
                        ctx.startActivity(Intent(ctx, CommandPaletteActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        onClose()
                    }
                    QuickAction("ASK ABOUT THIS") {
                        ctx.startActivity(Intent(ctx, com.mcpintelligence.fr3k.ui.ask.AskAboutThisActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        onClose()
                    }
                    QuickAction("OPEN DASHBOARD") {
                        ctx.startActivity(Intent(ctx, com.mcpintelligence.fr3k.ui.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        onClose()
                    }
                    QuickAction("CLOSE", accent = Fr3kPalette.TextDim) { onClose() }
                }
            }
        }
    }
}

@Composable
private fun AdapterRows(caps: Set<String>, termuxState: IntegrationState) {
    val registry = listOf(
        "Hermes" to caps.any { it.startsWith("agent.") },
        "MeshCore" to caps.any { it.startsWith("meshcore.") },
        "Meshtastic" to caps.any { it.startsWith("meshtastic.") },
        "GPS" to caps.any { it.startsWith("location.") },
    )
    registry.forEach { (name, online) ->
        AdapterStatusRow(
            name = name,
            statusText = if (online) "ONLINE" else "OFFLINE",
            color = if (online) Fr3kPalette.Ok else Fr3kPalette.Err,
            status = if (online) ProtoDeviceStatus.ONLINE else ProtoDeviceStatus.OFFLINE,
        )
    }
    // Termux uses the REAL probed state (§4), green only when operational.
    val termux = TermuxHealth.statusLabel(termuxState)
    AdapterStatusRow(
        name = "Termux",
        statusText = termux.label,
        color = termux.level.asColor(),
        status = if (termuxState.operational) ProtoDeviceStatus.ONLINE else ProtoDeviceStatus.OFFLINE,
    )
}

@Composable
private fun AdapterStatusRow(name: String, statusText: String, color: GraphicsColor, status: ProtoDeviceStatus) {
    val animatedColor by animateColorAsState(color, tween(350), label = "adapterColor")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            color = Fr3kPalette.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm),
        ) {
            Text(
                text = statusText,
                color = animatedColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            StatusDot(
                status = status,
                size = 6.dp,
            )
        }
    }
}

private fun TermuxHealth.TermuxStatusLevel.asColor(): GraphicsColor = when (this) {
    TermuxHealth.TermuxStatusLevel.OK -> Fr3kPalette.Ok
    TermuxHealth.TermuxStatusLevel.WARN -> Fr3kPalette.Warn
    TermuxHealth.TermuxStatusLevel.ERR -> Fr3kPalette.Err
}

@Composable
private fun QuickAction(label: String, accent: GraphicsColor = Fr3kPalette.Accent, onClick: () -> Unit) {
    Fr3kRowItem(
        label = label,
        accent = accent,
        onClick = onClick,
        trailing = {
            Text("›", color = accent, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        },
    )
}
