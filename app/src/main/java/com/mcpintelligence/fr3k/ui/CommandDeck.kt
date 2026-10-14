package com.mcpintelligence.fr3k.ui

import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.app.ActivityManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.appcontrol.releaseProcedures
import com.mcpintelligence.fr3k.appcontrol.ModGuideCatalogue
import com.mcpintelligence.fr3k.capability.*
import com.mcpintelligence.fr3k.hud.HudOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SurfaceEntry(val title: String, val explanation: String, val requirement: String, val screen: String)
private val surfaces = listOf(
    SurfaceEntry("Command palette", "Search registered commands and review their requirements.", "Depends on the selected command", "palette.CommandPaletteActivity"),
    SurfaceEntry("Ask about this", "Choose the shared context your AI receives, then send a question.", "Configured AI provider; network", "ask.AskAboutThisActivity"),
    SurfaceEntry("Smart clipboard", "Clean a copied URL or act on text you explicitly shared.", "Clipboard text", "clipboard.SmartClipboardActivity"),
    SurfaceEntry("Screenshot actions", "Review the capture surface and its available actions.", "Android screen-capture consent", "screenshot.ScreenshotActivity"),
    SurfaceEntry("App control", "Select installed apps, pair wireless shell, import modules and review installation.", "Android grants and compatible modules", "appcontrol.AppControlActivity"),
    SurfaceEntry("Integrations", "Pair the HUD shell, connect Termux and configure your provider.", "On-device wireless pairing for shell", "integrations.IntegrationsActivity"),
    SurfaceEntry("Automations", "Inspect trigger rules and their execution logs.", "The rule's own permissions", "automation.AutomationActivity"),
    SurfaceEntry("BLACKWAVE devices", "Connect your companion and inspect discovered fleet devices.", "Companion and physical hardware", "blackwave.BlackwaveActivity"),
    SurfaceEntry("Open on another device", "Review a destination and payload before a handoff.", "Discovered compatible peer", "handoff.DeviceHandoffActivity"),
    SurfaceEntry("Developer context", "Inspect foreground app metadata and copy a diagnostic summary.", "Usage access for app detection", "devoverlay.DeveloperOverlayActivity"),
    SurfaceEntry("Diagnostics", "Run permission and provider checks; export the observed results.", "No extra permission to open", "diagnostics.DiagnosticsActivity"),
    SurfaceEntry("Settings & provider", "Manage consent, overlay preferences and OpenCode Go credentials.", "Provider key for remote AI", "settings.SettingsActivity"),
    SurfaceEntry("Setup walkthrough", "Revisit permission setup inside Android's own consent screens.", "Your explicit permission choices", "onboarding.OnboardingActivity"),
)

@Composable
fun CommandDeck(activity: ComponentActivity, onOpenPalette: () -> Unit, onAskAboutThis: () -> Unit) {
    val app = Fr3kApplication.get()
    var tab by rememberSaveable { mutableStateOf("Deck") }
    var guideCount by remember { mutableIntStateOf(releaseProcedures().size) }
    var env by remember { mutableStateOf(ModEnvironment()) }
    var hudRunning by remember { mutableStateOf(false) }
    var mcpRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun open(screen: String) { activity.startActivity(Intent().setClassName(activity, "com.mcpintelligence.fr3k.ui.$screen")) }
    fun guides() = open("mods.ModsActivity")
    LaunchedEffect(Unit) {
        while (true) {
            env = withContext(Dispatchers.IO) { ModGuideCatalogue.environment(app) }
            @Suppress("DEPRECATION")
            hudRunning = (activity.getSystemService(ActivityManager::class.java)).getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == HudOverlayService::class.java.name }
            guideCount = withContext(Dispatchers.IO) { ModGuideCatalogue.all(app).size }
            mcpRunning = app.mcpServer.running
            delay(1500)
        }
    }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF15132D), Fr3kPalette.Bg, Color(0xFF0A1529))))
        .windowInsetsPadding(WindowInsets.systemBars)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("fR3k", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Fr3kPalette.Text)
                Text("YOUR DEVICE. YOUR CONTROL.", fontSize = 10.sp, letterSpacing = 1.5.sp, color = Fr3kPalette.Accent)
            }
            TextButton(onClick = { guides() }, modifier = Modifier.semantics { contentDescription = "deck-guides" }) { Text("APP MODS ↗") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Deck", "Planes", "Workflows", "Agent", "Journal", "Tools").forEach { item ->
                FilterChip(selected = tab == item, onClick = { tab = item }, label = { Text(item) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "deck-tab-$item" })
            }
        }
        key(tab) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (tab) {
                    "Deck" -> {
                        Text("Command Deck", style = MaterialTheme.typography.displaySmall, color = Fr3kPalette.Text)
                        DeckText("Inspect, change and combine your apps. Every action has a target, a requirement and a receipt.")
                        Button(onClick = { guides() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text("Explore ${guideCount} guided capabilities →")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Fr3kPanel(modifier = Modifier.weight(1f), title = "BRIDGE") { StateLine("Accessibility", env.bridgeBound, "Enable HUD Accessibility") }
                            Fr3kPanel(modifier = Modifier.weight(1f), title = "HUD") { StateLine("Floating tools", hudRunning, "Start the HUD below") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Fr3kPanel(modifier = Modifier.weight(1f), title = "SHELL") { StateLine("App operations", env.privilegedShell, "ADB bootstrap · Planes") }
                            Fr3kPanel(modifier = Modifier.weight(1f), title = "ROOT") { StateLine("Root operations", env.privilegedRoot, "Root bootstrap · Planes") }
                        }
                        Fr3kPanel(title = "CONNECTED INTELLIGENCE") {
                            DeckText("AI · ${app.activeProviderLabel}\n${app.activeModelLabel}")
                            DeckText(if (mcpRunning) "MCP · READY · localhost:${app.mcpServer.port()}" else "MCP · STOPPED · start in Agent", if (mcpRunning) Fr3kPalette.Ok else Fr3kPalette.TextDim)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onOpenPalette, modifier = Modifier.weight(1f)) { Text("Commands") }
                            OutlinedButton(onClick = onAskAboutThis, modifier = Modifier.weight(1f)) { Text("Ask AI") }
                        }
                        Fr3kPanel(title = "Floating workspace") {
                            DeckText("Tap the orb for chat; long-press for tools. Drag a window by its title and resize by its corner. Outside taps stay with the app underneath.")
                            Button(onClick = {
                                app.settings.update { it.copy(hudEnabled = true) }
                                when {
                                    !android.provider.Settings.canDrawOverlays(activity) -> activity.startActivity(com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher.overlayIntent(activity))
                                    Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), MainActivity.REQ_NOTIF)
                                    else -> activity.startForegroundService(Intent(activity, HudOverlayService::class.java))
                                }
                            }, modifier = Modifier.semantics { contentDescription = "deck-hud-start" }) { Text("Start HUD") }
                            OutlinedButton(onClick = { app.settings.update { it.copy(hudEnabled = false) }; activity.stopService(Intent(activity, HudOverlayService::class.java)) }, enabled = hudRunning) { Text("Stop HUD") }
                        }
                        Fr3kPanel(title = "What this build cannot do") {
                            DeckText("Selected apps support launch and typed app-op controls. Import a compatible module to patch a single-APK target. Android still enforces shell/root permissions, signer checks and system consent. Hardware actions need the real device.")
                        }
                        TextButton(onClick = { tab = "Journal" }) { Text("View operation receipts →") }
                    }
                    "Planes" -> {
                        Text("Four planes of control", style = MaterialTheme.typography.titleLarge)
                        DeckText("Choose where the work happens. A running service is evidence; an installed companion is not.")
                        listOf(
                            Triple("BRIDGE", env.bridgeBound, "Reads UI trees, taps fingerprinted controls, types, asserts and masks. Needs HUD Accessibility. Undo masks with unmask; a tap has no automatic inverse."),
                            Triple("PATCH", true, "Embedded engine prepares and signs an owned APK. No root or companion. Review the target and data-loss warning before Android's installer. Undo by reviewing a reinstall of the archived original."),
                            Triple("PRIVILEGED · SHELL", env.privilegedShell, "Typed app-op read, set, read-back and restore. Needs one-time ADB bootstrap from App control. Restore the recorded original mode; service may need restarting after reboot."),
                            Triple("PRIVILEGED · ROOT", env.privilegedRoot, "Root-gated component operations and private directory names. Needs HUD's service started from a root shell. No private file contents are exposed. Restore the original component state.")
                        ).forEach { (name, ready, explanation) ->
                            Fr3kPanel(title = name) {
                                DeckText(if (ready) "AVAILABLE · verify the exact target before running" else "NEEDS SETUP", if (ready) Fr3kPalette.Ok else Fr3kPalette.Warn)
                                DeckText(explanation)
                                TextButton(onClick = { guides() }) { Text("Requirements & walkthrough →") }
                            }
                        }
                        OutlinedButton(onClick = { open("appcontrol.AppControlActivity") }) { Text("Open app selection & wireless debug") }
                        StackExplainer()
                    }
                    "Workflows" -> WorkflowDeck()
                    "Agent" -> {
                        Text("Your agent, on your device", style = MaterialTheme.typography.titleLarge)
                        DeckText("MCP exposes the same registry, guides and evidence to your own tools. Localhost only; bearer token required. Execution tools can modify targets: connect only an agent you trust.")
                        var message by remember { mutableStateOf("") }
                        var reveal by remember { mutableStateOf(false) }
                        Fr3kPanel(title = if (mcpRunning) "MCP · running" else "MCP · stopped") {
                            DeckText(if (mcpRunning) "http://127.0.0.1:${app.mcpServer.port()}" else "No listener running")
                            Button(onClick = { scope.launch { message = withContext(Dispatchers.IO) { runCatching { "Listening on port ${app.startMcp()}" }.getOrElse { "Failed: ${it.message}" } }; mcpRunning = app.mcpServer.running } }, enabled = !mcpRunning) { Text("Start MCP") }
                            OutlinedButton(onClick = { app.stopMcp(); mcpRunning = false; reveal = false; message = "Stopped" }, enabled = mcpRunning) { Text("Stop MCP") }
                            TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide bearer token" else "Show bearer token") }
                            if (reveal) DeckText(app.mcpServer.token)
                            if (message.isNotEmpty()) DeckText(message)
                        }
                        val toolList = remember { app.mcpServer.toolList() }
                        repeat(toolList.length()) { i -> val tool = toolList.getJSONObject(i)
                            Fr3kPanel(title = tool.getString("name")) { DeckText(tool.getString("description")) }
                        }
                        OutlinedButton(onClick = { open("settings.SettingsActivity") }) { Text("Connect OpenCode Go / provider") }
                    }
                    "Journal" -> JournalDeck()
                    else -> {
                        Text("Tools & connections", style = MaterialTheme.typography.titleLarge)
                        DeckText("Every surface explains what it needs before you open it. Capability walkthroughs include limits and undo.")
                        surfaces.forEachIndexed { i, entry ->
                            Fr3kPanel(modifier = Modifier.fillMaxWidth().clickable { open(entry.screen) }.semantics { contentDescription = "deck-tool-$i" }, title = entry.title) {
                                DeckText(entry.explanation)
                                DeckText("REQUIRES · ${entry.requirement}", Fr3kPalette.Accent)
                                Text("Open →", color = Fr3kPalette.Accent, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                        DeckText("fR3k HUD ${app.identity.appVersion} · ${app.identity.platform}\n${app.identity.deviceId}")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable private fun StateLine(name: String, ready: Boolean, setup: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("${if (ready) "● READY" else "○ SETUP"}\n$name", color = if (ready) Fr3kPalette.Ok else Fr3kPalette.Warn, fontSize = 14.sp)
        if (!ready) Text(setup, color = Fr3kPalette.TextDim, fontSize = 12.sp)
    }
}
@Composable internal fun DeckText(text: String, color: Color = Fr3kPalette.TextDim) {
    Text(text, color = color, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 4.dp))
}
@Composable private fun StackExplainer() {
    var stage by remember { mutableIntStateOf(0) }
    val stages = listOf("App UI" to "You choose a goal; this alone makes no changes.", "Plan" to "Review a fixed list of actions, their cost, risk and inverse.", "Capability" to "Only registered actions bound to an exact installed build can run.", "Plane" to "Bridge, Patch or Privileged performs the typed operation.", "Target" to "Package, version, signer and APK hashes must match. Drift is refused.")
    Fr3kPanel(title = "Where an action happens") {
        stages.forEachIndexed { i, (name, _) -> TextButton(onClick = { stage = i }) { Text("${i + 1}. $name ${if (stage == i) "←" else "↓"}") } }
        DeckText(stages[stage].second)
    }
}

@Composable private fun WorkflowDeck() {
    val app = Fr3kApplication.get()
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<CapabilityDescriptor>>(emptyList()) }
    val session = app.workflowSession
    val selected by session.draft.collectAsState()
    val note by session.note.collectAsState()
    val busy by session.busy.collectAsState()
    var reviewed by remember { mutableStateOf(false) }
    var goal by remember { mutableStateOf("") }
    var compiling by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entries = withContext(Dispatchers.IO) { app.capabilityHost.registry(true).all().map { it.descriptor } } }
    Text("Workflow combinator", style = MaterialTheme.typography.titleLarge)
    DeckText("Choose capabilities in execution order. Every step keeps its own exact target. Review before running; a flow stops on failure.")
    OutlinedTextField(value = goal, onValueChange = { goal = it; reviewed = false }, label = { Text("Goal · for example hide the banner") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        compiling = true; reviewed = false
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { com.mcpintelligence.fr3k.appcontrol.PlanCompiler(app.capabilityHost).compile(goal) } }
            result.onSuccess { compiled ->
                val plan = compiled.plan
                if (plan == null) session.note.value = "REFUSED: ${compiled.refusal}"
                else {
                    session.draft.value = plan.steps.mapNotNull { app.capabilityHost.registry().find(it.capabilityId, it.identity) }
                    session.note.value = "Plan built for review. No action executed."
                }
            }.onFailure { session.note.value = "REFUSED: ${it.message}" }
            compiling = false
        }
    }, enabled = goal.isNotBlank() && !busy && !compiling) { Text(if (compiling) "Building…" else "Build reviewable plan") }
    if (entries.isEmpty()) DeckText("Select an installed app in App control, enable its controls, then reopen this tab.", Fr3kPalette.Warn)
    entries.forEach { cap ->
        OutlinedButton(onClick = { session.draft.value = selected + cap; reviewed = false }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("+ ${cap.id}") }
    }
    selected.forEachIndexed { i, cap ->
        Fr3kPanel(title = "${i + 1}. ${cap.id}") {
            DeckText(cap.summary)
            DeckText("${cap.identity.packageName}\nVersion ${cap.identity.versionCode}\nSigner ${cap.identity.signerSha256}\nIdentity ${cap.identity.fingerprint}")
            ModGuideCatalogue.all(app).firstOrNull { cap.id in it.capabilityIds }?.let { guide -> DeckText("Limits: ${guide.limits}\nUndo: ${guide.reversible}") }
        }
    }
    if (selected.isNotEmpty()) {
        DeckText("Cost: local execution; no provider call. Target apps come to foreground. All target identities are checked before the first step.")
        Row { Checkbox(checked = reviewed, onCheckedChange = { reviewed = it }, enabled = !busy); Text("I reviewed each target, limit and undo", modifier = Modifier.padding(top = 12.dp).weight(1f)) }
        Button(onClick = {
            val spec = WorkflowSpec("ui.combinator", "Operator workflow", 1, selected.map { WorkflowStep(it.id, it.identity) })
            session.run(spec); reviewed = false
        }, enabled = reviewed && !busy) { Text(if (busy) "Running…" else "Run reviewed workflow") }
        OutlinedButton(onClick = { session.draft.value = emptyList(); reviewed = false; session.note.value = "Selection cleared; executed changes remain in Journal." }, enabled = !busy) { Text("Clear selection") }
    }
    if (note.isNotEmpty()) DeckText(note)
    StackExplainer()
}

@Composable private fun JournalDeck() {
    val app = Fr3kApplication.get()
    var records by remember { mutableStateOf<List<EvidenceRecord>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    suspend fun refresh() { records = withContext(Dispatchers.IO) { app.capabilityHost.journalSnapshot().toList().reversed() } }
    LaunchedEffect(Unit) { refresh() }
    Text("Journal & evidence", style = MaterialTheme.typography.titleLarge)
    DeckText("Recorded outcomes and real inverses. A tap with no recorded state change is not presented as undoable.")
    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Filter package, capability or status") }, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { scope.launch { refresh() } }) { Text("Refresh receipts") }
    val shown = records.filter { "$it".contains(query, ignoreCase = true) }
    DeckText("${shown.size} of ${records.size} recorded operations")
    if (records.isEmpty()) DeckText("No operations yet. Run a reviewed capability to see its observed outcome here.")
    shown.forEach { entry ->
        Fr3kPanel(title = "${entry.status} · ${entry.capabilityId}") {
            DeckText("${entry.packageName}\n${java.util.Date(entry.startedAtMillis)} · ${entry.durationMs}ms\n${entry.note}")
            DeckText("Identity: ${entry.appFingerprint}\n${if (entry.undoable) "Recorded inverse" else "No automatic undo"}: ${entry.inverse.toJson()}", Fr3kPalette.Accent)
        }
    }
}
