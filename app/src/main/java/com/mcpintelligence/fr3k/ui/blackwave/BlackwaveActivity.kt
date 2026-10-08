package com.mcpintelligence.fr3k.ui.blackwave

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.integrations.blackwave.FleetDeviceCard
import com.mcpintelligence.fr3k.ui.Fr3kBadge
import com.mcpintelligence.fr3k.ui.Fr3kButton
import com.mcpintelligence.fr3k.ui.Fr3kDimens
import com.mcpintelligence.fr3k.ui.Fr3kDivider
import com.mcpintelligence.fr3k.ui.Fr3kGhostButton
import com.mcpintelligence.fr3k.ui.Fr3kGradientText
import com.mcpintelligence.fr3k.ui.Fr3kOutlineButton
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kPanel
import com.mcpintelligence.fr3k.ui.Fr3kRowItem
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import kotlinx.coroutines.launch

/**
 * §6 main BLACKWAVE screen.
 *
 * Shows the bridge status, the role manifest, the fleet, and the
 * feature-scope availability. Feature rows are honest: OTA / Live Apply
 * only report "available" when the authenticated role actually grants the
 * scope AND at least one device is online, otherwise they explain why and
 * offer a **Configure →** action into the right wizard step.
 */
class BlackwaveActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Fr3kTheme { BlackwaveScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun BlackwaveScreen(onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var probe by remember { mutableStateOf<BridgeProbe?>(null) }
    var probing by remember { mutableStateOf(false) }

    fun runProbe() {
        scope.launch {
            probing = true
            probe = probeBridge(app)
            probing = false
        }
    }

    LaunchedEffect(Unit) { runProbe() }

    Box(modifier = Modifier.fillMaxSize().background(Fr3kPalette.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Fr3kDimens.SpacingLg),
            verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingMd),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingMd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Fr3kGradientText(
                        text = "▰ BLACKWAVE",
                        style = MaterialTheme.typography.headlineSmall,
                        colors = listOf(Fr3kPalette.Accent, Fr3kPalette.Magenta),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "§6 setup · §7 add device",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
                Fr3kOutlineButton(text = "BACK", onClick = onClose)
            }

            val current = probe
            Fr3kPanel(
                title = "bridge",
                subtitle = when {
                    probing -> "probing…"
                    current?.reachable == true -> "reachable"
                    else -> "offline"
                },
                accent = when {
                    current?.reachable == true -> Fr3kPalette.Ok
                    else -> Fr3kPalette.Accent
                },
            ) {
                StatusRow(
                    "endpoint",
                    if (current?.reachable == true) BwLevel.PASS else BwLevel.FAIL,
                    current?.endpoint ?: "not probed",
                )
                StatusRow(
                    "credential",
                    if (current?.hasCredential == true) BwLevel.PASS else BwLevel.FAIL,
                    if (current?.hasCredential == true) "stored (encrypted)" else "missing — set in SETUP",
                )
                StatusRow(
                    "identity",
                    BwLevel.PASS,
                    app.identity.deviceId,
                )
                StatusRow(
                    "role",
                    current?.let { if (it.role != null && !it.role.isExpired) BwLevel.PASS else BwLevel.WARN } ?: BwLevel.FAIL,
                    current?.let {
                        val role = it.role
                        if (role == null) "no role manifest"
                        else "trust ${role.trust_tier} · scopes ${role.allowed_scopes.size}" +
                            if (role.isExpired) " · EXPIRED" else ""
                    } ?: "unprobed",
                )
                StatusRow(
                    "fleet",
                    if ((current?.fleetOnline ?: 0) > 0) BwLevel.PASS else BwLevel.WARN,
                    if (current == null) "unprobed"
                    else "${current.fleetOnline}/${current.fleetAccounted} online" +
                        if (current.fleet?.stale == true) " · stale cache" else "",
                )
                Spacer(Modifier.height(Fr3kDimens.SpacingSm))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Fr3kButton(
                        text = if (probing) "PROBING…" else "REPROBE",
                        onClick = { runProbe() },
                        enabled = !probing,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Fr3kPanel(title = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
                    Fr3kButton(
                        text = "SET UP BLACKWAVE",
                        onClick = { context.startActivity(Intent(app, BlackwaveSetupActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Fr3kGhostButton(
                        text = "ADD DEVICE",
                        onClick = { context.startActivity(Intent(app, BlackwaveAddDeviceActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            FeatureAccessPanel(app, current, onConfigure = { step ->
                val intent = Intent(app, BlackwaveSetupActivity::class.java)
                    .putExtra(BlackwaveSetupActivity.EXTRA_INITIAL_STEP, step.ordinal)
                context.startActivity(intent)
            })

            Fr3kPanel(title = "enabled scopes", subtitle = "${app.settings.settings.value.blackwaveEnabledScopes.size} granted") {
                val enabled = app.settings.settings.value.blackwaveEnabledScopes
                if (enabled.isEmpty()) {
                    Text(
                        "no scopes enabled yet — run SET UP BLACKWAVE",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingXs)) {
                        enabled.sorted().forEach { s ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Fr3kDimens.RadiusChip))
                                    .background(Fr3kPalette.SurfaceLow, RoundedCornerShape(Fr3kDimens.RadiusChip))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("›", color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace)
                                Text(
                                    text = BwScopes.label(s),
                                    color = Fr3kPalette.Text,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Fr3kBadge(text = s, color = Fr3kPalette.AccentDim)
                            }
                        }
                    }
                }
            }

            Fr3kPanel(title = "fleet", subtitle = "${current?.fleet?.devices?.size ?: 0} devices") {
                val devices = current?.fleet?.devices.orEmpty()
                if (devices.isEmpty()) {
                    Text(
                        "no devices visible — probe the bridge or add one manually",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
                        devices.sortedBy { it.display_name }.forEach { card ->
                            FleetRow(card) {
                                context.startActivity(
                                    Intent(app, BlackwaveAddDeviceActivity::class.java)
                                        .putExtra(BlackwaveAddDeviceActivity.EXTRA_MODEL_ID, card.model_id)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Fr3kDimens.SpacingLg))
        }
    }
}

/**
 * §6 feature-scope panel. Live Apply / OTA must never surface as unexplained
 * disabled buttons — each row explains the exact reason and gives a direct
 * **Configure →** action into the matching wizard step.
 */
@Composable
private fun FeatureAccessPanel(app: Fr3kApplication, probe: BridgeProbe?, onConfigure: (WizardStep) -> Unit) {
    val online = probe?.fleetOnline ?: 0
    val hasOta = probe?.hasScope("ota.apply") == true
    val hasLive = probe?.hasScope("profile.apply") == true

    Fr3kPanel(
        title = "feature access",
        subtitle = if (online > 0) "$online online" else "no devices",
        accent = if (online > 0 && (hasOta || hasLive)) Fr3kPalette.Ok else Fr3kPalette.Accent,
    ) {
        // OTA
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Fr3kDimens.SpacingXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("OTA FIRMWARE", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = when {
                        hasOta && online > 0 -> "available on $online authenticated device(s)"
                        !hasOta -> "OTA unavailable: role lacks ota.apply scope."
                        else -> "OTA unavailable: no authenticated OTA-capable device connected."
                    },
                    color = if (hasOta && online > 0) Fr3kPalette.Ok else Fr3kPalette.TextDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            }
            if (hasOta && online > 0) {
                Fr3kBadge(text = "available", color = Fr3kPalette.Ok)
            } else {
                Fr3kOutlineButton(text = "CONFIGURE →", onClick = { onConfigure(WizardStep.OTA_CONFIG) })
            }
        }
        Fr3kDivider(Modifier.padding(vertical = Fr3kDimens.SpacingSm))
        // Live Apply
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Fr3kDimens.SpacingXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("LIVE APPLY", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = when {
                        hasLive && online > 0 -> "available on $online authenticated device(s)"
                        !hasLive -> "Live Apply unavailable: role lacks profile.apply scope."
                        else -> "Live Apply unavailable: no authenticated device with profile.apply scope connected."
                    },
                    color = if (hasLive && online > 0) Fr3kPalette.Ok else Fr3kPalette.TextDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            }
            if (hasLive && online > 0) {
                Fr3kBadge(text = "available", color = Fr3kPalette.Ok)
            } else {
                Fr3kOutlineButton(text = "CONFIGURE →", onClick = { onConfigure(WizardStep.LIVE_APPLY_CONFIG) })
            }
        }
    }
}

@Composable
private fun FleetRow(card: FleetDeviceCard, onClick: () -> Unit) {
    Fr3kRowItem(
        label = card.display_name.ifBlank { card.model_id },
        detail = listOf(card.model_id, card.device_class, if (card.firmware_version.isNotBlank()) "fw ${card.firmware_version}" else null)
            .filterNotNull().joinToString(" · "),
        accent = Fr3kPalette.Text,
        onClick = onClick,
        trailing = {
            Fr3kBadge(
                text = card.online,
                color = when (card.online) {
                    "online" -> Fr3kPalette.Ok
                    "offline" -> Fr3kPalette.Err
                    else -> Fr3kPalette.TextDim
                },
            )
        },
    )
}