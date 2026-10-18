package com.mcpintelligence.fr3k.ui.blackwave

import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveLocalClient
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun BlackwaveLocalPanel(requestedPackage: String? = null, requestedOperation: String? = null) {
    val context = LocalContext.current
    val client = remember(context) { BlackwaveLocalClient(context) }
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf<List<Pair<String, Set<String>>>>(emptyList()) }
    var status by remember { mutableStateOf("Checking same-phone BLACKWAVE…") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        installed = withContext(Dispatchers.IO) { client.packages.mapNotNull { name ->
            try { name to client.signers(name) } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
        } }
        status = if (installed.isEmpty()) "Install BLACKWAVE to connect locally. Optional gateways remain below." else "Review the installed certificate, then approve HUD in BLACKWAVE Settings."
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connect BLACKWAVE", style = MaterialTheme.typography.titleMedium)
        LocalAssociationGuide()
        Text(status)
        installed.forEach { (name, signers) ->
            BlackwaveLocalMissions(client, name, requestedOperation.takeIf { requestedPackage == name })
            Text(name, fontFamily = FontFamily.Monospace)
            signers.forEach { Text(it, fontFamily = FontFamily.Monospace) }
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) { client.approve(name, signers) }
                        val health = client.query(name, JSONObject().put("version", 1).put("method", "health"))
                        status = "Local connection: ${health.getString("status")} · authority ${health.getString("authority")}"
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { status = e.message ?: "Local connection failed" }
                    finally { busy = false }
                }
            }) { Text("Approve certificate and connect locally") }
            OutlinedButton(onClick = {
                try { context.startActivity(Intent().setComponent(ComponentName(name, "com.fr3k.blackwave.MainActivity")).putExtra("blackwave.destination", "Settings")) }
                catch (_: android.content.ActivityNotFoundException) { status = "BLACKWAVE is no longer installed" }
            }) { Text("Open BLACKWAVE Settings to link HUD") }
            TextButton(enabled = !busy, onClick = { scope.launch {
                withContext(Dispatchers.IO) { client.revoke(name) }; status = "Local association removed"
            } }) { Text("Disconnect local BLACKWAVE") }
        }
    }
}
