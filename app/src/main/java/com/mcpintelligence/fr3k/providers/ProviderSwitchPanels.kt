package com.mcpintelligence.fr3k.providers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.integrations.providers.AgentProvider
import com.mcpintelligence.fr3k.integrations.providers.ProviderSetupService
import com.mcpintelligence.fr3k.integrations.providers.ValidationStatus
import com.mcpintelligence.fr3k.ui.Fr3kDimens
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import kotlinx.coroutines.launch

/** Which provider the shared agent answers with, reusing keys that are already on this device. */
@Composable
fun ActiveProviderPanel(service: ProviderSetupService, modifier: Modifier = Modifier) {
    val settings by service.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
        Text(
            "Active: ${settings.activeAgentProvider} / ${service.selectedModel(settings)}",
            color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
        AgentProvider.entries.forEach { provider ->
            val saved = service.hasSavedKey(provider)
            Button(
                onClick = {
                    busy = true
                    status = "Verifying saved ${provider.title} key…"
                    scope.launch {
                        try {
                            val result = service.activateWithStoredKey(provider)
                            status = result.message
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && saved && settings.activeAgentProvider != provider.id,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        settings.activeAgentProvider == provider.id -> "${provider.title} · ACTIVE"
                        saved -> "USE SAVED ${provider.title.uppercase()} KEY"
                        else -> "${provider.title} · NO SAVED KEY"
                    }
                )
            }
        }
        Text(
            "Switching verifies the stored key with one small request before anything changes; the key is never re-entered or displayed.",
            color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
        )
        if (status.isNotBlank()) Text(status, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

/** Battery-optimisation state and the system dialog, so the app is actually listed as exempt. */
@Composable
fun BatteryOptimisationPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var ignoring by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
        Text(
            if (ignoring) "Exempt from battery optimisation: voice can keep listening in the background."
            else "Battery optimisation still applies: Android may stop voice and background work.",
            color = if (ignoring) Fr3kPalette.Ok else Fr3kPalette.Warn,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        )
        Button(
            onClick = {
                val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(direct) }.isFailure) {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            },
            enabled = !ignoring,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (ignoring) "ALREADY EXEMPT" else "ALLOW BACKGROUND VOICE") }
        Button(
            onClick = { ignoring = power.isIgnoringBatteryOptimizations(context.packageName) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("RECHECK EXEMPTION") }
    }
}
