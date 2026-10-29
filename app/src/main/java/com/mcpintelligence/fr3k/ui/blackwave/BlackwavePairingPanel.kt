package com.mcpintelligence.fr3k.ui.blackwave

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
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
import org.json.JSONObject

/**
 * A pairing payload is minted by the gateway, not by the HUD: only the gateway knows the request id,
 * nonce and its own certificate pin. What the HUD can do is check a payload before it is pasted into
 * the companion and hand it over in one tap.
 */
internal object BlackwavePairing {
    const val API = "fr3k-blackwave-mobile/1"

    data class Parsed(val host: String, val port: Int, val pin: String)

    /** Same rules the companion applies, so a payload is rejected here instead of there. */
    fun validate(raw: String): Result<Parsed> = runCatching {
        val json = JSONObject(raw.trim())
        require(json.optString("kind") == "blackwave-pairing") { "not a blackwave-pairing payload" }
        require(json.optString("api") == API) { "gateway API must be $API" }
        require(json.optInt("v", 1) == 1) { "unsupported pairing version" }
        val host = json.getString("host")
        require(host.matches(Regex("[A-Za-z0-9.-]+")) && !host.startsWith(".") && !host.endsWith(".")) { "invalid host" }
        val port = json.getInt("port")
        require(port in 1..65535) { "invalid port" }
        val pin = json.getString("gateway_spki_sha256")
        require(pin.matches(Regex("[0-9a-fA-F]{64}"))) { "gateway pin must be 64 hex characters" }
        require(json.optString("request_id").isNotBlank()) { "missing request_id" }
        require(json.optString("nonce").isNotBlank()) { "missing nonce" }
        Parsed(host, port, pin.lowercase())
    }
}

@Composable
internal fun BlackwavePairingPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var payload by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var parsed by remember { mutableStateOf<BlackwavePairing.Parsed?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "The gateway issues the pairing payload; the HUD cannot invent one. On the gateway host run " +
                "blackwave-fleet serve, create a pairing request, then run blackwave-fleet serve-mobile. " +
                "Paste the QR JSON it prints below.",
            color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
        )
        OutlinedTextField(
            value = payload,
            onValueChange = { payload = it; parsed = null; status = null },
            label = { Text("pairing QR JSON") },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            maxLines = 6,
        )
        Button(
            enabled = payload.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                BlackwavePairing.validate(payload).fold(
                    onSuccess = { result ->
                        parsed = result
                        status = "Valid payload for ${result.host}:${result.port}. Pin ${result.pin.take(16)}…"
                    },
                    onFailure = { error ->
                        parsed = null
                        status = "Rejected: ${error.message}"
                    },
                )
            },
        ) { Text("CHECK PAYLOAD") }
        if (parsed != null) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("blackwave pairing", payload.trim()))
                    status = "Copied. Paste it into BLACKWAVE → SET UP BLACKWAVE → PAIR GATEWAY."
                },
            ) { Text("COPY FOR BLACKWAVE") }
        }
        status?.let {
            Text(
                it,
                color = if (parsed != null) Fr3kPalette.Ok else Fr3kPalette.Warn,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
        }
    }
}
