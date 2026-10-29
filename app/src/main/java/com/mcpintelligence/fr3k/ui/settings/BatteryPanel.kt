package com.mcpintelligence.fr3k.ui.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.ui.Fr3kPalette

/**
 * Battery-optimisation state and the system dialog.
 *
 * The app declares REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, but vendor battery screens do not always
 * list every app, so the direct request is the reliable path — and the state is read back rather
 * than assumed.
 */
@Composable
internal fun BatteryOptimisationPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var ignoring by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
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
