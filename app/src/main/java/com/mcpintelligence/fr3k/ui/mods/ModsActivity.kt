package com.mcpintelligence.fr3k.ui.mods

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.appcontrol.ModGuideCatalogue
import com.mcpintelligence.fr3k.capability.ModCategory
import com.mcpintelligence.fr3k.capability.ModEnvironment
import com.mcpintelligence.fr3k.capability.ModGuide
import com.mcpintelligence.fr3k.capability.ModGuideStatus
import com.mcpintelligence.fr3k.capability.ModReadiness
import com.mcpintelligence.fr3k.capability.ModRequirement
import com.mcpintelligence.fr3k.ui.Fr3kBadge
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kPanel
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import kotlinx.coroutines.launch

/**
 * APP MODS — every capability this build has, each with the walkthrough that gets you to it.
 *
 * The screen's job is to remove the guesswork: what a thing does, what it needs, the steps in order,
 * what to say to run it, what actually executes, what it cannot do, and how to undo it. A guide whose
 * requirements are not met on this device says so on the card instead of failing silently when tried.
 */
class ModsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Fr3kTheme {
                Box(modifier = Modifier.fillMaxSize().background(Fr3kPalette.Bg)) {
                    ModsScreen(onClose = { finish() })
                }
            }
        }
    }
}

@Composable
private fun ModsScreen(onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    var environment by remember { mutableStateOf(ModEnvironment()) }
    var search by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { environment = withContext(Dispatchers.IO) { ModGuideCatalogue.environment(app) }; delay(2000) } }
    var category by remember { mutableStateOf<ModCategory?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var mcpStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    var guides by remember { mutableStateOf(if (com.mcpintelligence.fr3k.appcontrol.CapabilityCatalogue.labMode(app)) ModGuideCatalogue.all() else com.mcpintelligence.fr3k.appcontrol.releaseProcedures()) }
    LaunchedEffect(Unit) { guides = withContext(Dispatchers.IO) { app.capabilityHost.registry(true); ModGuideCatalogue.all(app) } }
    val ordered = remember(guides, environment) { ModGuideStatus.order(guides, environment) }
    val shown = ordered.filter { (category == null || it.category == category) && (search.isBlank() || "${it.title} ${it.oneLiner} ${it.id} ${it.requirements.joinToString { r -> r.label }}".contains(search, ignoreCase = true)) }
    val readyCount = ordered.count { ModGuideStatus.readiness(it, environment) == ModReadiness.READY }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(
                text = "▰ fR3k // APP MODS",
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
            Text(
                text = "Everything this build can actually do, with the walkthrough for each one. " +
                    "Requirements are checked against this device, not assumed.",
                color = Fr3kPalette.TextDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }

        // ---------------------------------------------------------------- live capability state
        Fr3kPanel(title = "what this device can do right now") {
            Column(
                modifier = Modifier.semantics { contentDescription = "mods-status" },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip("BRIDGE", environment.bridgeBound)
                    StatusChip("SHELL", environment.privilegedShell)
                    StatusChip("ROOT", environment.privilegedRoot)
                }
                Text(
                    text = "$readyCount of ${ordered.size} guides ready · " +
                        "${ordered.size - readyCount} need setup (they say which step)",
                    color = Fr3kPalette.TextDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                if (!environment.bridgeBound) {
                    Text(
                        text = ModRequirement.BRIDGE.howTo,
                        color = Fr3kPalette.Warn,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
                if (!environment.privilegedShell) {
                    Text(
                        text = ModRequirement.ADB_BOOTSTRAP.howTo,
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
                OutlinedButton(
                    onClick = { scope.launch { environment = withContext(Dispatchers.IO) { ModGuideCatalogue.environment(app) } } },
                    modifier = Modifier.semantics { contentDescription = "mods-recheck" },
                ) { Text("RE-CHECK DEVICE", fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
            }
        }

        OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search capabilities and guides") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "mods-search" })
        Text("${shown.size} of ${ordered.size} guides", color = Fr3kPalette.TextDim)
        if (shown.isEmpty()) Text("No matches. Clear search or choose ALL.", color = Fr3kPalette.Warn)

        // ---------------------------------------------------------------- filters
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("ALL", category == null, "mods-filter-ALL") { category = null }
            ModCategory.entries.forEach { entry ->
                FilterChip(entry.label, category == entry, "mods-filter-${entry.label}") { category = entry }
            }
        }
        category?.let {
            Text(
                text = it.blurb,
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }

        // ---------------------------------------------------------------- guides
        shown.forEach { guide ->
            key(guide.id) { GuideCard(
                guide = guide,
                environment = environment,
                expanded = expanded == guide.id,
                onToggle = { expanded = if (expanded == guide.id) null else guide.id },
            ) }
        }

        // ---------------------------------------------------------------- surfaces
        Fr3kPanel(title = "connect it to your own tools") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "The localhost MCP surface exposes the same capabilities and these same guides to " +
                        "your own agent. Token required; nothing is written without approval.",
                    color = Fr3kPalette.TextDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                mcpStatus = runCatching {
                                    val port = app.startMcp()
                                    val token = runCatching {
                                        org.json.JSONObject(
                                            java.io.File(app.filesDir, "mcp.json").readText(),
                                        ).optString("token")
                                    }.getOrDefault("")
                                    "http://127.0.0.1:$port  token $token"
                                }.getOrElse { "could not start: ${it.message}" }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                        modifier = Modifier.semantics { contentDescription = "mods-mcp-start" },
                    ) { Text("START MCP", fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = {
                            runCatching { app.stopMcp() }
                            mcpStatus = "stopped"
                        },
                        modifier = Modifier.semantics { contentDescription = "mods-mcp-stop" },
                    ) { Text("STOP", fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                }
                mcpStatus?.let {
                    Text(
                        text = it,
                        color = Fr3kPalette.Ok,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.semantics { contentDescription = "mods-mcp-status" },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = {
                            app.startActivity(
                                Intent(app, com.mcpintelligence.fr3k.ui.appcontrol.AppControlActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        modifier = Modifier.semantics { contentDescription = "mods-open-appcontrol" },
                    ) { Text("APP CONTROL", fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                    OutlinedButton(
                        onClick = {
                            app.startActivity(
                                Intent(app, com.mcpintelligence.fr3k.ui.integrations.IntegrationsActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        modifier = Modifier.semantics { contentDescription = "mods-open-integrations" },
                    ) { Text("INTEGRATIONS", fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                }
            }
        }

        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("CLOSE", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusChip(name: String, live: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (live) Fr3kPalette.Ok.copy(alpha = 0.15f) else Fr3kPalette.Surface)
            .border(1.dp, if (live) Fr3kPalette.Ok else Fr3kPalette.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "$name ${if (live) "LIVE" else "—"}",
            color = if (live) Fr3kPalette.Ok else Fr3kPalette.TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Fr3kPalette.Accent else Fr3kPalette.Surface)
            .heightIn(min = 48.dp)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = label,
            color = if (selected) Fr3kPalette.Bg else Fr3kPalette.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun GuideCard(
    guide: ModGuide,
    environment: ModEnvironment,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val readiness = ModGuideStatus.readiness(guide, environment)
    val missing = ModGuideStatus.missing(guide, environment)
    val cardShape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            // Glass-ish: a translucent surface over the dark background with a hairline border.
            .background(Fr3kPalette.Surface.copy(alpha = 0.72f))
            .border(1.dp, if (readiness == ModReadiness.READY) Fr3kPalette.Border else Fr3kPalette.Warn.copy(alpha = 0.5f), cardShape),
    ) {
        Column(
            modifier = Modifier
                .clickable { onToggle() }
                .padding(12.dp)
                .semantics { contentDescription = "mods-guide-${guide.id}" },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = guide.title,
                    color = Fr3kPalette.Text,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f),
                )
                Fr3kBadge(
                    text = readiness.label,
                    color = if (readiness == ModReadiness.READY) Fr3kPalette.Ok else Fr3kPalette.Warn,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${guide.category.label} · ${guide.oneLiner}",
                color = Fr3kPalette.TextDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
            if (missing.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "needs: " + missing.joinToString(", ") { it.label },
                    color = Fr3kPalette.Warn,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (expanded) "▾ hide walkthrough" else "▸ walkthrough",
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .semantics { contentDescription = "mods-steps-${guide.id}" },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Section("what it does", guide.whatItDoes, Fr3kPalette.Text)
                Section("runs on", guide.runsOn, Fr3kPalette.Text)
                if (missing.isNotEmpty()) {
                    missing.forEach { requirement ->
                        Section("setup: ${requirement.label}", requirement.howTo, Fr3kPalette.Warn)
                    }
                }
                Text(
                    text = "walkthrough",
                    color = Fr3kPalette.Accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                guide.steps.forEachIndexed { index, step ->
                    Text(
                        text = "${index + 1}. $step",
                        color = Fr3kPalette.Text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
                Section("goal for Workflows or MCP render_plan", guide.examplePhrase, Fr3kPalette.Magenta)
                if (guide.capabilityIds.isNotEmpty()) {
                    Section("what actually runs", guide.capabilityIds.joinToString(", "), Fr3kPalette.TextDim)
                }
                Section("limits — read this", guide.limits, Fr3kPalette.Warn)
                Section("undo", guide.reversible, Fr3kPalette.Ok)
            }
        }
    }
}

@Composable
private fun Section(label: String, body: String, color: Color) {
    Column {
        Text(
            text = label,
            color = Fr3kPalette.Accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(text = body, color = color, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}
