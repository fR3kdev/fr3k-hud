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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.hud.BuildConfig
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveOta
import com.mcpintelligence.fr3k.integrations.blackwave.FleetDeviceCard
import com.mcpintelligence.fr3k.integrations.blackwave.OtaPresentation
import com.mcpintelligence.fr3k.integrations.blackwave.OtaProgress
import com.mcpintelligence.fr3k.integrations.blackwave.OtaStatus
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

            OtaSection(app, current)

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

/**
 * §6 wireless-flash (OTA) action. Gated on the existing feature-access probe:
 * the trigger is enabled only when the role grants `ota.apply` and a device is
 * online. Staging is client-side only — the gateway owns execution and rollback.
 * A debug-only simulation toggle exercises the pipeline without hardware.
 */
@Composable
private fun OtaSection(app: Fr3kApplication, probe: BridgeProbe?) {
    val scope = rememberCoroutineScope()
    val online = probe?.fleetOnline ?: 0
    val hasOta = probe?.hasScope("ota.apply") == true
    val realReady = hasOta && online > 0

    val target = remember(probe?.fleet) {
        probe?.fleet?.devices?.firstOrNull { it.online == "online" }
            ?: probe?.fleet?.devices?.firstOrNull()
    }
    val targetLabel = target?.let { t -> t.display_name.ifBlank { t.model_id } } ?: "no enrolled device"

    var progress by remember { mutableStateOf<OtaProgress?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var simulate by remember { mutableStateOf(false) }
    var simulateFailure by remember { mutableStateOf(false) }

    val active = progress?.active == true
    val canStart = !active && (simulate || realReady)
    val accent = when {
        progress?.status == OtaStatus.COMMITTED -> Fr3kPalette.Ok
        progress?.terminal == true -> Fr3kPalette.Err
        else -> Fr3kPalette.Accent
    }

    fun start() {
        job?.cancel()
        val modelId = target?.model_id?.takeIf { it.isNotBlank() } ?: "sim-blackwave"
        val deviceId = (target?.device_id ?: target?.model_id)?.takeIf { it.isNotBlank() } ?: "sim:blackwave"
        val simulated = simulate || target == null
        val initial = OtaProgress(
            deviceId = deviceId,
            modelId = modelId,
            stageIndex = 0,
            stageLabel = OtaPresentation.STAGES.first(),
            status = OtaStatus.REQUESTED,
            detail = "Staging signed A/B update via gateway",
            simulation = simulated,
        )
        progress = initial
        job = scope.launch {
            try {
                if (simulated) {
                    runSimulatedOta(initial, simulateFailure) { progress = it }
                } else {
                    runGatewayOta(app, initial) { progress = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress = progress?.copy(
                    status = OtaStatus.FAILED,
                    committed = false,
                    detail = e.message ?: "OTA staging failed",
                )
            }
        }
    }

    Fr3kPanel(
        title = "wireless flash (ota)",
        subtitle = when {
            active -> "staging…"
            progress?.status == OtaStatus.COMMITTED -> "committed"
            progress?.terminal == true -> "fail-closed"
            else -> "idle"
        },
        accent = accent,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
            Text(
                text = "Signed A/B artifact staged through the gateway. The HUD never flashes, reboots or rolls back a target itself.",
                color = Fr3kPalette.TextDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
            Text(
                text = when {
                    !hasOta -> "OTA unavailable: role lacks ota.apply scope."
                    online == 0 -> "OTA unavailable: no authenticated OTA-capable device connected."
                    else -> "Target: $targetLabel · $online online"
                },
                color = if (realReady) Fr3kPalette.Ok else Fr3kPalette.TextDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
            Fr3kButton(
                text = when {
                    active -> "FLASHING…"
                    progress?.terminal == true -> "RESTAGE UPDATE"
                    else -> "WIRELESS FLASH (OTA)"
                },
                onClick = { start() },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth(),
            )
            if (BuildConfig.DEBUG) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm),
                ) {
                    Fr3kOutlineButton(
                        text = if (simulate) "SIM: ON" else "SIM: OFF",
                        onClick = { simulate = !simulate },
                        enabled = !active,
                        accent = if (simulate) Fr3kPalette.Warn else Fr3kPalette.TextDim,
                        modifier = Modifier.weight(1f),
                    )
                    Fr3kOutlineButton(
                        text = if (simulateFailure) "FAIL@VERIFY: ON" else "FAIL@VERIFY: OFF",
                        onClick = { simulateFailure = !simulateFailure },
                        enabled = !active && simulate,
                        accent = if (simulateFailure) Fr3kPalette.Err else Fr3kPalette.TextDim,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            val p = progress
            if (p != null) {
                OtaStageChecklist(p)
                OtaProgressBar(p)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = p.status.name.replace('_', ' ') + " · " + p.stageLabel,
                        color = accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (p.active) {
                        Fr3kOutlineButton(text = "CANCEL", onClick = {
                            job?.cancel()
                            progress = p.copy(
                                status = OtaStatus.FAILED,
                                committed = false,
                                detail = "Cancelled by operator — gateway retains rollback control",
                            )
                        })
                    } else if (p.status != OtaStatus.IDLE) {
                        Fr3kOutlineButton(text = "CLEAR", onClick = { progress = null })
                    }
                }
                p.detail?.let {
                    Text(it, color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                }
                p.targetFirmware?.let {
                    Text("target firmware: $it", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                }
                if (p.simulation) {
                    Text(
                        "SIMULATION — no real flashing, no RF, no device mutation.",
                        color = Fr3kPalette.Warn,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                    )
                }
                if (p.terminal && p.status != OtaStatus.COMMITTED) {
                    Text(
                        "FAIL-CLOSED: success is not claimed. The gateway owns rollback and recovery.",
                        color = Fr3kPalette.Err,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun OtaStageChecklist(p: OtaProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        OtaPresentation.STAGES.forEachIndexed { index, stage ->
            val reached = p.stageIndex >= 0 && index <= p.stageIndex
            val isCurrent = index == p.stageIndex && p.active
            val marker = when {
                isCurrent -> "▶"
                p.status == OtaStatus.COMMITTED && reached -> "✓"
                reached && p.terminal -> if (index == p.stageIndex) "✕" else "✓"
                reached -> "✓"
                else -> "·"
            }
            val colour = when {
                isCurrent -> Fr3kPalette.Accent
                p.status == OtaStatus.COMMITTED && reached -> Fr3kPalette.Ok
                index == p.stageIndex && p.terminal -> Fr3kPalette.Err
                reached -> Fr3kPalette.Ok
                else -> Fr3kPalette.TextDim
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Fr3kBadge(text = marker, color = colour)
                Text(
                    text = stage,
                    color = colour,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun OtaProgressBar(p: OtaProgress) {
    val fraction = ((p.stageIndex + 1).toFloat() / OtaPresentation.STAGES.size).coerceIn(0f, 1f)
    val color = when {
        p.status == OtaStatus.COMMITTED -> Fr3kPalette.Ok
        p.terminal -> Fr3kPalette.Err
        else -> Fr3kPalette.Accent
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Fr3kPalette.SurfaceLow),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(color, Fr3kPalette.Magenta.copy(alpha = 0.9f)))),
        )
    }
}

private suspend fun runSimulatedOta(
    initial: OtaProgress,
    failAtVerify: Boolean,
    update: (OtaProgress) -> Unit,
) {
    var p = initial
    OtaPresentation.STAGES.forEachIndexed { index, stage ->
        if (failAtVerify && stage == "Verifying hash") {
            p = p.copy(
                stageIndex = index,
                stageLabel = "Verification failed",
                status = OtaStatus.RUNNING,
                detail = "Artifact hash mismatch — refusing to boot candidate (fail-closed)",
            )
            update(p)
            delay(700)
            p = p.copy(stageLabel = "Rollback initiated", detail = "Target reverting to previous A/B slot")
            update(p)
            delay(700)
            p = p.copy(
                status = OtaStatus.ROLLED_BACK,
                committed = false,
                stageLabel = "Rollback complete",
                detail = "Artifact verification failed",
            )
            update(p)
            return
        }
        delay(650)
        val committed = stage == OtaPresentation.STAGES.last()
        p = p.copy(
            stageIndex = index,
            stageLabel = stage,
            status = if (committed) OtaStatus.COMMITTED else OtaStatus.RUNNING,
            committed = committed,
            detail = if (committed) "Update committed on target after health check" else stage,
            targetFirmware = if (committed) BlackwaveOta.SIMULATED_FIRMWARE else p.targetFirmware,
        )
        update(p)
    }
}

private suspend fun runGatewayOta(
    app: Fr3kApplication,
    initial: OtaProgress,
    update: (OtaProgress) -> Unit,
) {
    val client = app.blackwaveBridgeClient
    val deviceId = initial.deviceId ?: error("no OTA target device")
    val modelId = initial.modelId ?: error("no OTA target model")
    var p = withContext(Dispatchers.IO) { client.stageOta(deviceId, modelId) }
        .getOrElse { throw IllegalStateException(it.message ?: "OTA stage request failed") }
        .copy(simulation = false)
    update(p)
    val jobId = p.jobId ?: throw IllegalStateException("gateway did not return an OTA job id")
    var guard = 0
    while (!p.terminal && guard < 1800) {
        delay(1000)
        p = withContext(Dispatchers.IO) { client.pollOta(jobId, deviceId, modelId) }
            .getOrElse { throw IllegalStateException(it.message ?: "OTA poll failed") }
        update(p)
        guard++
    }
    if (!p.terminal) throw IllegalStateException("OTA job did not reach a terminal state")
    if (p.status != OtaStatus.COMMITTED) throw IllegalStateException(p.detail ?: "OTA did not commit")
}