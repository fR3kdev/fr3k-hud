package com.mcpintelligence.fr3k.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.hud.HudOverlayService
import com.mcpintelligence.fr3k.permissions.PermissionRegistry
import com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher
import com.mcpintelligence.fr3k.ui.Fr3kAmbientBackground
import com.mcpintelligence.fr3k.ui.Fr3kBadge
import com.mcpintelligence.fr3k.ui.Fr3kButton
import com.mcpintelligence.fr3k.ui.Fr3kDimens
import com.mcpintelligence.fr3k.ui.Fr3kGhostButton
import com.mcpintelligence.fr3k.ui.Fr3kGradientText
import com.mcpintelligence.fr3k.ui.Fr3kGradients
import com.mcpintelligence.fr3k.ui.Fr3kOutlineButton
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kPanel
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import com.mcpintelligence.fr3k.ui.settings.SettingsActivity

private const val REQ_NOTIF = 7009

/**
 * First-run onboarding (§9): WELCOME → PERMISSIONS → READY.
 *
 * The step order is fixed (welcome, permissions, ready); every step is
 * skippable and honours the §34 doctrine by showing the REAL permission
 * state (never a papered-over "all good") with one-tap GRANT actions that
 * reuse PermissionRegistry / SpecialPermissionLauncher. Completion writes
 * `onboardingDone = true` to AppSettings and starts the HUD if the overlay
 * + notification perms are now granted (same rule as MainActivity's
 * auto-start). Skipping behaves identically to completing.
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Fr3kTheme {
                OnboardingScreen(
                    activity = this@OnboardingActivity,
                    onDone = { completeOnboarding() },
                    onSkip = { completeOnboarding() },
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Permission state is recomputed on the next recomposition; nothing
        // to do here beyond letting the OS dialog close.
    }

    /** Finish/skip both write the flag so the gate disappears for ever. */
    private fun completeOnboarding() {
        val app = Fr3kApplication.get()
        val alreadyDone = app.settings.settings.value.onboardingDone
        if (!alreadyDone) {
            app.settings.update { it.copy(onboardingDone = true) }
        }
        maybeStartHudAfterGrant()
        finish()
    }

    /**
     * Onboarding completion is exactly the moment the HUD may become
     * grantable — mirrors MainActivity's auto-start rule: foreground
     * service only when overlay + notifications are BOTH granted.
     */
    private fun maybeStartHudAfterGrant() {
        val canOverlay = SpecialPermissionLauncher.canDrawOverlays(this)
        val canNotif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (canOverlay && canNotif) {
            startForegroundService(Intent(this, HudOverlayService::class.java))
        }
    }
}

private enum class OnboardingStep(val title: String) {
    VOICE("FR3K HUD · CHECK YOUR VOICE"),
    WELCOME("FR3K HUD · ONE-TIME SETUP"),
    PERMISSIONS("GRANT WHAT FR3K NEEDS"),
    READY("READY · FR3K HUD"),
}

private val stepOrder = OnboardingStep.entries

@Composable
private fun OnboardingScreen(
    activity: OnboardingActivity,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val app = Fr3kApplication.get()
    var step by remember { mutableStateOf(OnboardingStep.VOICE) }
    // Bumped when we return from the overlay Settings round-trip so the
    // PERMISSIONS rows re-read their real grant state.
    var overlayReturnCheck by remember { mutableIntStateOf(0) }
    val canOverlay = remember(overlayReturnCheck) { SpecialPermissionLauncher.canDrawOverlays(activity) }
    val canNotif = remember(overlayReturnCheck) {
        Build.VERSION.SDK_INT < 33 ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    Fr3kAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Fr3kDimens.SpacingXl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OrbEmblem()
            Spacer(Modifier.height(Fr3kDimens.SpacingLg))
            StepDots(current = stepOrder.indexOf(step))
            Spacer(Modifier.height(Fr3kDimens.SpacingLg))
            Fr3kGradientText(
                text = step.title,
                style = MaterialTheme.typography.headlineMedium,
                colors = listOf(Fr3kPalette.AccentLift, Fr3kPalette.Ok),
            )
            Spacer(Modifier.height(Fr3kDimens.SpacingLg))

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val dir = stepOrder.indexOf(targetState) - stepOrder.indexOf(initialState)
                    ContentTransform(
                        targetContentEnter = fadeIn(tween(280)) + slideInHorizontally(tween(320)) { w -> dir * w / 8 },
                        initialContentExit = fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { w -> -dir * w / 10 },
                    )
                },
                label = "onbStep",
            ) { s ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingMd),
                ) {
                    when (s) {
                        OnboardingStep.WELCOME -> {
                            Fr3kPanel(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "An adaptive Android agent interface. Floating HUD orb, terminal + browser overlays, and a BLACKWAVE fleet bridge.",
                                    color = Fr3kPalette.Text,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Spacer(Modifier.height(Fr3kDimens.SpacingSm))
                                Text(
                                    text = "v${app.identity.appVersion} · ${app.identity.platform} · ${app.identity.deviceId.take(12)}…",
                                    color = Fr3kPalette.TextDim,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Fr3kButton(
                                text = "BEGIN SETUP",
                                onClick = { step = OnboardingStep.VOICE },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        OnboardingStep.VOICE -> {
                            // Voice is the first real step: it speaks a welcome, which proves the
                            // engine, language data and audio route before anything else is asked.
                            com.mcpintelligence.fr3k.ui.voice.VoiceHub()
                            Spacer(Modifier.height(Fr3kDimens.SpacingSm))
                            Fr3kButton(
                                text = "CONTINUE",
                                onClick = { step = OnboardingStep.WELCOME },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        OnboardingStep.PERMISSIONS -> {
                            PermissionRow(
                                label = "FLOATING HUD · SYSTEM_ALERT_WINDOW",
                                detail = "required to draw the orb over other apps",
                                granted = canOverlay,
                                onGrant = {
                                    activity.startActivity(SpecialPermissionLauncher.overlayIntent(activity))
                                    overlayReturnCheck++
                                },
                            )
                            PermissionRow(
                                label = "NOTIFICATIONS · POST_NOTIFICATIONS",
                                detail = if (Build.VERSION.SDK_INT < 33) "granted (SDK < 33)" else "required for the foreground HUD service",
                                granted = canNotif,
                                onGrant = {
                                    PermissionRegistry.request(
                                        activity,
                                        PermissionRegistry.Feature.HUD_ORB,
                                        REQ_NOTIF,
                                    )
                                },
                            )
                            Fr3kButton(
                                text = "CONTINUE",
                                onClick = { step = OnboardingStep.READY },
                                modifier = Modifier.fillMaxWidth().padding(top = Fr3kDimens.SpacingSm),
                            )
                            Text(
                                text = "skip for now — permission state stays visible in diagnostics",
                                color = Fr3kPalette.TextDim,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Fr3kDimens.RadiusChip))
                                    .clickable { onSkip() }
                                    .padding(8.dp),
                            )
                        }

                        OnboardingStep.READY -> {
                            val missing = buildList {
                                if (!canOverlay) add("SYSTEM_ALERT_WINDOW")
                                if (!canNotif) add("POST_NOTIFICATIONS")
                            }
                            Fr3kPanel(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = if (missing.isEmpty()) "all permissions granted ✓" else "permissions pending: ${missing.joinToString(", ")}",
                                    color = if (missing.isEmpty()) Fr3kPalette.Ok else Fr3kPalette.Warn,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "you can also manage everything from Settings",
                                    color = Fr3kPalette.TextDim,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            activity.startActivity(Intent(activity, SettingsActivity::class.java))
                                        }
                                        .padding(top = 6.dp),
                                )
                            }
                            Fr3kButton(
                                text = "GET STARTED",
                                onClick = onDone,
                                modifier = Modifier.fillMaxWidth().padding(top = Fr3kDimens.SpacingSm),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Fr3kDimens.SpacingLg))
            Fr3kGhostButton(
                text = "SKIP SETUP",
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
                contentColor = Fr3kPalette.TextDim,
            )
        }
    }
}

@Composable
private fun OrbEmblem() {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Fr3kPalette.Accent.copy(alpha = 0.28f), Fr3kPalette.AccentDim.copy(alpha = 0.08f), Color.Transparent),
                    ),
                ),
        )
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Fr3kGradients.AccentFill, CircleShape)
                .border(1.5.dp, Fr3kPalette.Ok.copy(alpha = 0.65f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "▰",
                color = Fr3kPalette.Bg,
                fontSize = 26.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun StepDots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm), verticalAlignment = Alignment.CenterVertically) {
        stepOrder.forEachIndexed { index, _ ->
            val done = index <= current
            val width by androidx.compose.animation.animateColorAsState(
                if (done) Fr3kPalette.Accent else Fr3kPalette.Border.copy(alpha = 0.5f),
                tween(260),
                label = "stepDot",
            )
            val segWidth by animateFloatAsState(if (done) 28f else 14f, tween(300), label = "stepSegW")
            Box(
                Modifier
                    .size(width = segWidth.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(width),
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    detail: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Fr3kPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = if (granted) Fr3kPalette.Ok else Fr3kPalette.Accent,
        contentPadding = Fr3kDimens.SpacingMd,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = Fr3kPalette.Text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(detail, color = Fr3kPalette.TextDim, style = MaterialTheme.typography.bodySmall)
            }
            if (granted) {
                Fr3kBadge(text = "granted ✓", color = Fr3kPalette.Ok)
            } else {
                Fr3kButton(text = "GRANT", onClick = onGrant)
            }
        }
    }
}