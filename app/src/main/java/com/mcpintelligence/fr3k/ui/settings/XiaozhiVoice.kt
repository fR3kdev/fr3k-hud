package com.mcpintelligence.fr3k.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import kotlinx.coroutines.flow.StateFlow

internal enum class VoicePhase { UNAVAILABLE, IDLE, CONNECTING, ACTIVATION_PENDING, READY, LISTENING, SPEAKING, ERROR }
internal data class VoiceStatus(val phase: VoicePhase, val message: String = "", val activationCode: String? = null)
internal object XiaozhiVoiceGate {
    fun canTalk(consent: Boolean, permission: Boolean, phase: VoicePhase) = consent && permission && phase == VoicePhase.READY
}

/** Adapter must map real controller state and audio start/stop; no invented enrollment identity.
 * Calls must be non-blocking. stop/close must cancel in-flight capture and release audio.
 * Consent is per screen visit, not persisted. connect must not run until explicit user action.
 * Output is either Android text-to-speech (default) or the server's own voice, never both. */
internal interface XiaozhiVoiceAdapter {
    val status: StateFlow<VoiceStatus>
    val speaksWithAndroidTts: Boolean
    fun setAndroidTtsOutput(enabled: Boolean)
    fun connect()
    fun startTalking()
    fun stopTalking()
    /** Ends the live channel but keeps this adapter reusable for a later connect. */
    fun disconnect()
    fun close()
}

@Composable
internal fun XiaozhiVoiceControls(adapter: XiaozhiVoiceAdapter?) {
    if (adapter == null) {
        Text("Voice unavailable: controller/audio adapter not connected. No fallback is used.")
        return
    }
    val status by adapter.status.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var consent by remember(adapter) { mutableStateOf(false) }
    var androidTts by remember(adapter) { mutableStateOf(adapter.speaksWithAndroidTts) }
    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(hasPermission()) }
    var permissionDenied by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = granted
        permissionDenied = !granted
    }
    DisposableEffect(adapter, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { permission = hasPermission(); if (!permission) adapter.stopTalking() }
                // Backgrounding ends the turn but keeps the adapter usable; only leaving the
                // screen disposes (and closes) it.
                Lifecycle.Event.ON_STOP -> { adapter.stopTalking(); consent = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); adapter.stopTalking(); adapter.close() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Xiaozhi session: ${status.phase.name.replace('_', ' ')}")
        if (status.message.isNotBlank()) Text(status.message)
        status.activationCode?.let { Text("Activation code: $it") }
        Text("Reply voice", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { androidTts = true; adapter.setAndroidTtsOutput(true) },
                enabled = !androidTts,
                modifier = Modifier.weight(1f),
            ) { Text("ANDROID") }
            Button(
                onClick = { androidTts = false; adapter.setAndroidTtsOutput(false) },
                enabled = androidTts,
                modifier = Modifier.weight(1f),
            ) { Text("XIAOZHI") }
        }
        Text(
            if (androidTts) "Replies are spoken by the Android system voice." else "Replies play the Xiaozhi server voice.",
            color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        )
        Text("Consent: connect to the selected Xiaozhi service and send microphone audio while held. Enrollment contract is not verified; no automatic registration.")
        Checkbox(checked = consent, onCheckedChange = { checked ->
            consent = checked
            if (!checked) adapter.stopTalking()
        })
        Button(onClick = { if (consent) adapter.connect() }, enabled = consent && status.phase in setOf(VoicePhase.IDLE, VoicePhase.ERROR)) { Text("CONNECT") }
        Button(onClick = { request.launch(Manifest.permission.RECORD_AUDIO) }, enabled = consent && !permission) { Text("ALLOW MICROPHONE") }
        if (permissionDenied) Text("Microphone permission denied. Grant it in Android settings or retry.")
        val enabled = XiaozhiVoiceGate.canTalk(consent, permission, status.phase)
        val currentEnabled by rememberUpdatedState(enabled)
        // Separate press/release from permission prompting: granting permission never starts capture.
        Text(if (enabled || status.phase == VoicePhase.LISTENING) "HOLD TO TALK" else "Hold to talk unavailable", modifier = Modifier
            .padding(12.dp)
            .pointerInput(adapter) {
                detectTapGestures(onPress = {
                    if (currentEnabled && hasPermission()) {
                        adapter.startTalking()
                        try { tryAwaitRelease() } finally { adapter.stopTalking() }
                    }
                })
            })
        Button(onClick = { adapter.stopTalking(); adapter.disconnect(); consent = false }) { Text("DISCONNECT") }
    }
}
