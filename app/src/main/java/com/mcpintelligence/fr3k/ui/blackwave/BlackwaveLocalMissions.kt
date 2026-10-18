package com.mcpintelligence.fr3k.ui.blackwave

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveLocalClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

@Composable
fun BlackwaveLocalMissions(client: BlackwaveLocalClient, packageName: String, requestedOperation: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember(packageName) { context.getSharedPreferences("blackwave-mission-$packageName", android.content.Context.MODE_PRIVATE) }
    var identities by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var nextDevices by remember { mutableStateOf<Int?>(null) }
    var selected by rememberSaveable(packageName) { mutableStateOf(saved.getString("target_id", null)) }
    var requestId by rememberSaveable(packageName) { mutableStateOf(saved.getString("request_id", null) ?: UUID.randomUUID().toString()) }
    var jobId by rememberSaveable(packageName, requestedOperation) { mutableStateOf(requestedOperation ?: saved.getString("operation_id", null)) }
    var job by remember { mutableStateOf<JSONObject?>(null) }
    var status by remember { mutableStateOf("Refresh local fleet after linking. Actions come from BLACKWAVE's current manifest.") }
    var busy by remember { mutableStateOf(false) }
    suspend fun request(method: String, body: JSONObject = JSONObject()): JSONObject = client.query(packageName, body.put("version", 1).put("method", method))
    suspend fun loadDevices(offset: Int, replace: Boolean) {
        val page = request("devices", JSONObject().put("offset", offset))
        val devices = page.getJSONArray("devices")
        val values = (0 until devices.length()).mapNotNull { i -> devices.getJSONObject(i).let {
            if (it.getBoolean("revoked")) null else it.getString("identity_ref") to (it.getString("alias") + " · " + it.getString("device_id"))
        } }
        val next = if (page.isNull("next_offset")) null else page.getInt("next_offset")
        require(next == null || next > offset) { "Invalid fleet continuation cursor" }
        identities = (if (replace) values else identities + values).distinctBy { it.first }
        nextDevices = next
        status = "${identities.size} enrolled units loaded${if (next != null) "; more available" else ""}. Stored enrollment is separate from a live connection."
    }
    LaunchedEffect(jobId, packageName) {
        val id = jobId ?: return@LaunchedEffect
        suspend fun refresh(): Boolean {
            val result = request("operation", JSONObject().put("operation_id", id))
            job = result.optJSONObject("job")
            if (requestedOperation != null && job != null) {
                requestId = job!!.getString("request_id"); selected = job!!.getString("target_id")
                saved.edit().putString("operation_id", id).putString("request_id", requestId).putString("target_id", selected).apply()
            }
            return job?.optString("state") in listOf("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED")
        }
        try {
            if (refresh()) return@LaunchedEffect
            try {
                client.events(packageName, saved.getLong("event_cursor", 0)).first { page ->
                    val events = page.getJSONArray("events")
                    val relevant = (0 until events.length()).any { events.getJSONObject(it).getString("operation_id") == id }
                    val finished = relevant && refresh()
                    // Commit only after processing the durable page; wake-ups alone never advance this cursor.
                    withContext(Dispatchers.IO) { check(saved.edit().putLong("event_cursor", page.getLong("cursor")).commit()) }
                    finished
                }
            } catch (_: UnsupportedOperationException) {
                // Compatibility with the original two-transaction service.
                while (currentCoroutineContext().isActive) {
                    delay(1000)
                    if (refresh()) break
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { status = e.message ?: "Mission unavailable; reconnect to retrieve existing request" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Local missions", style = MaterialTheme.typography.titleMedium)
        Text(status)
        OutlinedButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try {
                loadDevices(0, true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "Local fleet failed" }
            finally { busy = false }
        } }) { Text("Refresh local enrolled units") }
        nextDevices?.let { offset ->
            OutlinedButton(enabled = !busy, onClick = { scope.launch {
                busy = true
                try { loadDevices(offset, false) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { status = e.message ?: "Fleet continuation unavailable" }
                finally { busy = false }
            } }) { Text("Load more enrolled units") }
        }
        identities.forEach { (identity, label) ->
            OutlinedButton(enabled = !busy, onClick = { selected = identity; requestId = UUID.randomUUID().toString(); jobId = null; job = null; saved.edit().putString("target_id", identity).putString("request_id", requestId).remove("operation_id").apply() }) {
                Text(if (selected == identity) "Selected: $label" else label)
            }
        }
        selected?.let { identity ->
            Text(identity, fontFamily = FontFamily.Monospace)
            Button(enabled = !busy, onClick = { scope.launch {
                busy = true
                try {
                    val available = request("capabilities", JSONObject().put("target_id", identity)).getJSONArray("local_actions")
                    val action = (0 until available.length()).map { available.getJSONObject(it) }.firstOrNull { it.getString("id") == "evidence.enrollment" }
                        ?: error("Enrollment evidence action is unavailable")
                    withContext(Dispatchers.IO) { check(saved.edit().putString("request_id", requestId).putString("target_id", identity).commit()) }
                    job = request("preview", JSONObject().put("request_id", requestId).put("target_id", identity).put("action", action.getString("id"))).getJSONObject("job")
                    jobId = job!!.getString("operation_id")
                    saved.edit().putString("operation_id", jobId).apply()
                    status = "Preview saved. Review and approve this exact mission in BLACKWAVE."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { status = e.message ?: "Preview failed; retain this request ID when reconnecting" }
                finally { busy = false }
            } }) { Text("Preview enrollment evidence") }
        }
        job?.let { current ->
            Text("Request: ${current.getString("request_id")}", fontFamily = FontFamily.Monospace)
            Text("Operation: ${current.getString("operation_id")}", fontFamily = FontFamily.Monospace)
            Text("State: ${current.getString("state")}")
            Text(current.getString("consequence"))
            Button(onClick = { context.startActivity(Intent().setComponent(ComponentName(packageName, "com.fr3k.blackwave.MainActivity")).putExtra("blackwave.destination", "Missions").putExtra("blackwave.operation", current.getString("operation_id"))) }) { Text("Open exact mission approval in BLACKWAVE") }
            if (current.getString("state") in listOf("PREVIEW", "APPROVED", "QUEUED")) TextButton(enabled = !busy, onClick = { scope.launch {
                try { job = request("cancel", JSONObject().put("operation_id", current.getString("operation_id"))).getJSONObject("job") }
                catch (e: Exception) { status = e.message ?: "Cancellation failed" }
            } }) { Text("Cancel local mission") }
            if (current.getString("state") in setOf("SUCCEEDED", "FAILED", "INTERRUPTED") && current.optJSONObject("receipt")?.optJSONObject("payload")?.has("artifact_sha256") == true) Button(enabled = !busy, onClick = { scope.launch {
                busy = true
                try {
                    val receipt = current.getJSONObject("receipt")
                    val payload = receipt.getJSONObject("payload")
                    val health = request("health")
                    check(payload.getString("controller_identity") == health.getString("controller_identity")) { "Receipt controller mismatch" }
                    check(payload.getString("operation_id") == current.getString("operation_id") && payload.getString("request_id") == current.getString("request_id") && payload.getString("target_id") == current.getString("target_id"))
                    val public = java.util.Base64.getDecoder().decode(health.getString("controller_public_key"))
                    val expectedIdentity = "bw:ed25519:sha256:" + MessageDigest.getInstance("SHA-256").digest(public).joinToString("") { "%02x".format(it) }
                    check(expectedIdentity == health.getString("controller_identity"))
                    check(public.size == 32)
                    val canonical = com.mcpintelligence.fr3k.integrations.blackwave.LocalReceiptBytes.canonical(payload)
                    val verifier = org.bouncycastle.crypto.signers.Ed25519Signer().apply {
                        init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(public, 0)); update(canonical, 0, canonical.size)
                    }
                    check(verifier.verifySignature(java.util.Base64.getDecoder().decode(receipt.getString("signature")))) { "Receipt signature invalid" }
                    val uri = Uri.parse(payload.getString("artifact_uri"))
                    check(uri.scheme == "content" && uri.authority == "$packageName.mission-evidence")
                    val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytesBounded(256 * 1024) } }
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    check(hash == payload.getString("artifact_sha256") && bytes.size == payload.getInt("artifact_bytes")) { "Artifact hash or length mismatch" }
                    val artifact = com.mcpintelligence.fr3k.integrations.blackwave.NativeDeviceEvidence.parse(bytes)
                    status = if (artifact.getString("protocol") == "blackwave-native-evidence/1") {
                        withContext(Dispatchers.IO) { com.mcpintelligence.fr3k.integrations.blackwave.NativeDeviceEvidence.verify(artifact, current, public) } + " Original request $requestId retained."
                    } else {
                        check(artifact.getString("protocol") == "blackwave-enrollment-evidence/1" && !artifact.getBoolean("device_live"))
                        "Verified signed receipt and ${bytes.size}-byte enrollment evidence artifact. Device live=false. Original request $requestId retained."
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { status = e.message ?: "Evidence verification failed" }
                finally { busy = false }
            } }) { Text("Verify receipt and read evidence") }
        }
    }
}

private fun java.io.InputStream.readBytesBounded(maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val block = ByteArray(8192)
    while (true) { val read = read(block); if (read < 0) break; require(output.size() + read <= maximum); output.write(block, 0, read) }
    return output.toByteArray()
}
