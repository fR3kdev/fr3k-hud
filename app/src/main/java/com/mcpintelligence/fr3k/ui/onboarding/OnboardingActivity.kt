package com.mcpintelligence.fr3k.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.AiProviderPreference
import kotlinx.coroutines.launch
import com.mcpintelligence.fr3k.hud.HudOverlayService
import com.mcpintelligence.fr3k.permissions.PermissionRegistry
import com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher
import com.mcpintelligence.fr3k.ui.Fr3kPalette
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
    // Force permission rows to refresh after Android's runtime grant dialog returns.
    val permissionRevision = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Fr3kTheme {
                OnboardingScreen(
                    activity = this@OnboardingActivity,
                    onDone = { completeOnboarding(skipped = false) },
                    onSkip = { completeOnboarding(skipped = true) },
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
        permissionRevision.intValue++
    }

    /** Finish/skip both write the flag so the gate disappears for ever. */
    private fun completeOnboarding(skipped: Boolean) {
        getSharedPreferences("fr3k.onboarding", MODE_PRIVATE).edit()
            .putString("outcome", if (skipped) "DEFERRED" else "COMPLETED")
            .putLong("updatedAtMillis", System.currentTimeMillis()).apply()
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
    WELCOME("FR3K HUD · ONE-TIME SETUP"),
    PERMISSIONS("GRANT WHAT FR3K NEEDS"),
    CONNECTIONS("CONNECT AND VERIFY DEVICE TOOLS"),
    AI("CONFIGURE AI PROVIDER + MODEL"),
    READY("READY · FR3K HUD"),
}

@Composable
private fun OnboardingScreen(
    activity: OnboardingActivity,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val app = Fr3kApplication.get()
    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }
    // Bumped when we return from the overlay Settings round-trip so the
    // PERMISSIONS rows re-read their real grant state.
    var overlayReturnCheck by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) overlayReturnCheck++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val workScope = rememberCoroutineScope()
    var aiChoice by remember { mutableStateOf(app.activeProviderId) }
    var aiKeyInput by remember { mutableStateOf("") }
    var aiModelFilter by remember { mutableStateOf("") }
    var aiMenuOpen by remember { mutableStateOf(false) }
    var aiOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var aiStatus by remember { mutableStateOf("Select provider and refresh the model catalogue.") }
    var termuxStatus by remember { mutableStateOf("NOT CHECKED") }
    LaunchedEffect(step, overlayReturnCheck) {
        if (step == OnboardingStep.CONNECTIONS) {
            com.mcpintelligence.fr3k.integrations.termux.TermuxHealth.invalidate()
            termuxStatus = com.mcpintelligence.fr3k.integrations.termux.TermuxHealth.statusLabel(
                com.mcpintelligence.fr3k.integrations.termux.TermuxHealth.probe(activity, app.termuxBridge)
            ).label
        }
    }
    val permissionRefresh = overlayReturnCheck + activity.permissionRevision.intValue
    val canOverlay = remember(permissionRefresh) { SpecialPermissionLauncher.canDrawOverlays(activity) }
    val canNotif = remember(permissionRefresh) {
        Build.VERSION.SDK_INT < 33 ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    Box(modifier = Modifier.fillMaxSize().background(Fr3kPalette.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            com.mcpintelligence.fr3k.ui.FeatureGuide("Setup")

            Text(
                text = step.title,
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(24.dp))

            when (step) {
                OnboardingStep.WELCOME -> {
                    Text(
                        text = "▰",
                        color = Fr3kPalette.Magenta,
                        fontSize = 56.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "An adaptive Android agent interface. Floating HUD orb, terminal + browser overlays, and a BLACKWAVE fleet bridge.",
                        color = Fr3kPalette.Text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "v${app.identity.appVersion} · ${app.identity.platform} · ${app.identity.deviceId.take(12)}…",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { step = OnboardingStep.PERMISSIONS },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                    ) { Text("BEGIN SETUP", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                }

                OnboardingStep.PERMISSIONS -> {
                    PermissionRow(
                        label = "FLOATING HUD · SYSTEM_ALERT_WINDOW",
                        detail = if (canOverlay) "granted" else "required to draw the orb over other apps",
                        granted = canOverlay,
                        onGrant = {
                            activity.startActivity(SpecialPermissionLauncher.overlayIntent(activity))
                            overlayReturnCheck++
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    PermissionRow(
                        label = "NOTIFICATIONS · POST_NOTIFICATIONS",
                        detail = if (Build.VERSION.SDK_INT < 33) "granted (SDK < 33)" else if (canNotif) "granted" else "required for the foreground HUD service",
                        granted = canNotif,
                        onGrant = {
                            PermissionRegistry.request(
                                activity,
                                PermissionRegistry.Feature.HUD_ORB,
                                REQ_NOTIF,
                            )
                        },
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { step = OnboardingStep.CONNECTIONS },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                    ) { Text("CONTINUE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "skip for now — permission state stays visible in diagnostics",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onSkip() }
                            .padding(6.dp),
                    )
                }

                OnboardingStep.CONNECTIONS -> {
                    Text("Status is checked on this phone. Android still requires you to approve sensitive access.", color = Fr3kPalette.TextDim)
                    val bridgeReady = com.mcpintelligence.fr3k.bridge.Fr3kBridgeService.connected()
                    PermissionRow("ACCESSIBILITY BRIDGE", if (bridgeReady) "CONNECTED" else "Enable FR3K HUD accessibility service in Android settings", bridgeReady) {
                        activity.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    val shellReady = runCatching {
                        com.mcpintelligence.fr3k.integrations.privileged.HudPrivilegedBridge.serviceOrNull() != null
                    }.getOrDefault(false)
                    PermissionRow("WIRELESS ADB / HUD SHELL", if (shellReady) "CONNECTED, verified service" else "Pair in Android Wireless debugging, then connect and verify shell", shellReady) {
                        activity.startActivity(Intent(activity, com.mcpintelligence.fr3k.ui.integrations.WirelessDebuggingActivity::class.java))
                    }
                    PermissionRow("TERMUX COMMAND BRIDGE", termuxStatus + " · real echo probe", termuxStatus == "CONNECTED") {
                        activity.startActivity(Intent(activity, com.mcpintelligence.fr3k.ui.integrations.IntegrationsActivity::class.java))
                    }
                    val micGranted = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    PermissionRow("MICROPHONE (OPTIONAL)", if (micGranted) "GRANTED" else "Needed only for voice input", micGranted) {
                        activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7010)
                    }
                    val cameraGranted = activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    PermissionRow("CAMERA (OPTIONAL)", if (cameraGranted) "GRANTED" else "Needed only for camera features", cameraGranted) {
                        activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), 7011)
                    }
                    val locationGranted = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    PermissionRow("LOCATION (OPTIONAL)", if (locationGranted) "GRANTED" else "Needed only for location features", locationGranted) {
                        activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 7012)
                    }
                    val batteryGranted = android.os.PowerManager::class.java.let {
                        (activity.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(activity.packageName)
                    }
                    PermissionRow("BATTERY OPTIMISATION", if (batteryGranted) "EXEMPT" else "Optional: reduce Android stopping the HUD in background", batteryGranted) {
                        activity.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                    val bluetoothGranted = Build.VERSION.SDK_INT < 31 ||
                        (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                         activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
                    PermissionRow("BLUETOOTH (OPTIONAL)", if (bluetoothGranted) "GRANTED" else "Needed for BLE nearby devices", bluetoothGranted) {
                        if (Build.VERSION.SDK_INT >= 31) activity.requestPermissions(
                            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 7013)
                    }
                    val installsAllowed = activity.packageManager.canRequestPackageInstalls()
                    PermissionRow("APK INSTALL REVIEW (OPTIONAL)", if (installsAllowed) "ALLOWED" else "Needed to open the Android installer for staged App Mod APKs", installsAllowed) {
                        activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:${activity.packageName}")))
                    }
                    val usageAllowed = (activity.getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager)
                        .unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                            android.os.Process.myUid(), activity.packageName) == android.app.AppOpsManager.MODE_ALLOWED
                    PermissionRow("USAGE ACCESS (OPTIONAL)", if (usageAllowed) "ALLOWED" else "Needed to inspect app usage statistics", usageAllowed) {
                        activity.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                    OutlinedButton(onClick = {
                        activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${activity.packageName}")))
                    }, modifier = Modifier.fillMaxWidth()) { Text("REVIEW ALL ANDROID APP PERMISSIONS") }
                    Button(onClick = { step = OnboardingStep.AI }, modifier = Modifier.fillMaxWidth()) {
                        Text("NEXT · AI PROVIDER AND MODELS")
                    }
                }

                OnboardingStep.AI -> {
                    Text("Select the AI provider, store its key privately, load the live model catalogue and pick a model here.", color = Fr3kPalette.Text)
                    listOf(AiProviderPreference.OPENCODE to "OPENCODE GO",
                        AiProviderPreference.OPENROUTER to "OPENROUTER").forEach { (id, title) ->
                        OutlinedButton(onClick = {
                            aiChoice = id
                            app.settings.update { it.copy(aiProviderPreference = id) }
                            aiOptions = emptyList()
                            aiStatus = "Selected $title. Refresh its model catalogue."
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text((if (aiChoice == id) "● " else "○ ") + title)
                        }
                    }
                    val credentialKey = if (aiChoice == AiProviderPreference.OPENROUTER)
                        app.settings.settings.value.openrouterApiKeyKey
                    else app.settings.settings.value.opencodeApiKeyKey
                    val hasKey = !app.secureStore.get(credentialKey).isNullOrBlank()
                    Text("API KEY: " + if (hasKey) "STORED (encrypted)" else "NOT STORED",
                        color = if (hasKey) Fr3kPalette.Ok else Fr3kPalette.Warn)
                    OutlinedTextField(value = aiKeyInput, onValueChange = { aiKeyInput = it },
                        label = { Text("Provider API key") }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = TextStyle(color = Fr3kPalette.Text))
                    Button(onClick = {
                        val key = aiKeyInput.trim()
                        if (key.isNotEmpty()) {
                            app.secureStore.put(credentialKey, key)
                            aiKeyInput = ""
                            aiStatus = "Key stored securely. Refresh models, then test provider."
                        } else aiStatus = "Enter a key, or use the provider's documented free tier where supported."
                    }, modifier = Modifier.fillMaxWidth()) { Text("SAVE API KEY") }
                    Button(onClick = {
                        workScope.launch {
                            aiStatus = "Fetching models…"
                            if (aiChoice == AiProviderPreference.OPENROUTER) {
                                app.openRouterProvider.refreshAllModels().fold(
                                    onSuccess = { models ->
                                        aiOptions = models.map { it.id to (it.name + if (it.free) " · FREE" else " · MAY BE PAID") }
                                        aiStatus = "${aiOptions.size} models loaded. Choose below."
                                    }, onFailure = { aiStatus = "Refresh failed: ${it.message}" })
                            } else {
                                app.openCodeProvider.refreshModels().fold(
                                    onSuccess = {
                                        aiOptions = app.openCodeProvider.availableModels().map { it.id to (it.id + if (it.isFree) " · FREE" else "") }
                                        aiStatus = "${aiOptions.size} catalogue entries loaded. Choose below."
                                    }, onFailure = { aiStatus = "Refresh failed: ${it.message}" })
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("FETCH ALL AVAILABLE MODELS") }
                    Text(aiStatus, color = Fr3kPalette.TextDim)
                    Button(onClick = { aiMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("MODEL ▼ · ${aiOptions.size} available")
                    }
                    DropdownMenu(expanded = aiMenuOpen, onDismissRequest = { aiMenuOpen = false },
                        modifier = Modifier.heightIn(max = 440.dp)) {
                        OutlinedTextField(value = aiModelFilter, onValueChange = { aiModelFilter = it },
                            label = { Text("Search models") }, modifier = Modifier.width(300.dp))
                        aiOptions.filter { it.first.contains(aiModelFilter, true) || it.second.contains(aiModelFilter, true) }
                            .forEach { (id, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    if (aiChoice == AiProviderPreference.OPENROUTER) {
                                        app.openRouterProvider.setModel(id)
                                        app.settings.update { it.copy(openrouterModel = id) }
                                    } else {
                                        app.openCodeProvider.setModel(id)
                                        app.settings.update { it.copy(opencodeModel = id) }
                                    }
                                    aiStatus = "Selected $id. Use TEST to confirm it can answer."
                                    aiMenuOpen = false
                                })
                            }
                    }
                    Text("ACTIVE: ${app.activeProviderLabel} · ${app.activeModelLabel}",
                        color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace)
                    Button(onClick = {
                        workScope.launch {
                            aiStatus = "Checking provider…"
                            val health = app.activeProvider.health()
                            aiStatus = (if (health.online) "ONLINE · " else "NOT VERIFIED · ") + (health.message ?: "unknown")
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("TEST PROVIDER CONNECTION") }
                    OutlinedButton(onClick = {
                        activity.startActivity(Intent(activity, SettingsActivity::class.java))
                    }, modifier = Modifier.fillMaxWidth()) { Text("ADVANCED AI SETTINGS") }
                    Button(onClick = { step = OnboardingStep.READY }, modifier = Modifier.fillMaxWidth()) {
                        Text("REVIEW SETUP")
                    }
                }

                OnboardingStep.READY -> {
                    val missing = buildList {
                        if (!canOverlay) add("SYSTEM_ALERT_WINDOW")
                        if (!canNotif) add("POST_NOTIFICATIONS")
                    }
                    if (missing.isEmpty()) {
                        Text(
                            text = "all permissions granted ✓",
                            color = Fr3kPalette.Ok,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    } else {
                        Text(
                            text = "permissions pending: ${missing.joinToString(", ")}",
                            color = Fr3kPalette.Warn,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    }
                    Text(
                        text = "Use CONNECTIONS and AI setup before relying on shell, Termux or chat. Missing optional access is never claimed as ready.", color = Fr3kPalette.Warn,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                    Text(
                        text = "you can also manage everything from Settings",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                activity.startActivity(Intent(activity, SettingsActivity::class.java))
                            }
                            .padding(top = 16.dp),
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                    ) { Text("GET STARTED", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("SKIP SETUP", fontFamily = FontFamily.Monospace) }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Fr3kPalette.Surface)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        if (granted) {
            Text("GRANTED", color = Fr3kPalette.Ok, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        } else {
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
            ) { Text("GRANT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
    }
}