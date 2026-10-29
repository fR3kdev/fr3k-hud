package com.mcpintelligence.fr3k.ui.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.integrations.voice.AndroidVoice
import com.mcpintelligence.fr3k.integrations.voice.VoiceChecklist
import com.mcpintelligence.fr3k.integrations.voice.VoiceState
import com.mcpintelligence.fr3k.setup.SetupAndroid
import com.mcpintelligence.fr3k.setup.SetupStatus
import com.mcpintelligence.fr3k.setup.SetupWalkthrough
import com.mcpintelligence.fr3k.ui.Fr3kDimens
import com.mcpintelligence.fr3k.ui.Fr3kPalette

/**
 * The HUD's voice entry point: first thing on the dashboard, and the same component in settings.
 *
 * On open it speaks a greeting. That single phrase tests the whole stack — engine, language data,
 * audio route — so a silent greeting is the setup prompt.
 *
 * Press once to start a conversation: it listens, answers out loud, then listens again until you
 * stop it. On the first press it checks the device first and, if anything is missing, walks the
 * operator through fixing that before pretending to listen.
 */
@Composable
fun VoiceHub(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val app = remember { Fr3kApplication.get() }
    val selectedSettings by app.settings.settings.collectAsState()
    val session = remember { app.agentSession }
    var showHermesSetup by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(VoiceState.IDLE) }
    var message by remember { mutableStateOf("Press start, then speak") }
    var lastReply by remember { mutableStateOf<String?>(null) }
    var conversing by remember { mutableStateOf(false) }
    var readiness by remember { mutableStateOf<AndroidVoice.Readiness?>(null) }
    var welcomed by remember { mutableStateOf(false) }
    var welcomeFailed by remember { mutableStateOf(false) }
    var walkthrough by remember { mutableStateOf<List<VoiceChecklist.Step>>(emptyList()) }
    var keyInput by remember { mutableStateOf("") }
    var keyStatus by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(SetupAndroid.progress(context)) }
    var rate by remember { mutableStateOf(com.mcpintelligence.fr3k.voice.VoiceTuning.rate(context)) }
    var muted by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var pitch by remember { mutableStateOf(com.mcpintelligence.fr3k.voice.VoiceTuning.pitch(context)) }
    val voice = remember(session) {
        AndroidVoice(context, session, onState = { newState, text ->
            main.post {
                state = newState
                message = text
                if (newState == VoiceState.ERROR) lastReply = null
                if (newState == VoiceState.ERROR || newState == VoiceState.IDLE) conversing = false
            }
        })
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voice.onPermissionResult(granted)
        readiness = voice.readiness()
        if (granted) walkthrough = VoiceChecklist.missing(deviceOf(readiness, context))
    }

    fun refresh() { readiness = voice.readiness() }
    DisposableEffect(voice) {
        refresh()
        onDispose { runCatching { voice.close() } }
    }
    // Spoken greeting on open: one phrase tests engine, language and audio together.
    LaunchedEffect(voice) {
        if (Greeted.thisProcess) return@LaunchedEffect
        Greeted.thisProcess = true
        voice.speakWelcome("Welcome to FR3K") { ok ->
            welcomed = true
            if (!ok) { welcomeFailed = true; message = "Voice is not set up — see the steps below" }
            else message = "Welcome spoken. Press start and talk."
        }
        main.postDelayed({
            if (!welcomed) {
                welcomeFailed = true
                message = "No speech came out — Android voice still needs setup"
            }
        }, 8000)
    }
    val check = readiness
    val device = deviceOf(check, context)
    val facts = SetupAndroid.facts(
        context,
        microphone = check?.microphone ?: micGranted(context),
        tts = check?.ttsEngine ?: false,
        recogniser = check?.recogniser ?: false,
        provider = device.provider,
    )
    val nextItem = SetupWalkthrough.next(progress, facts)
    val needsSetup = nextItem != null || !VoiceChecklist.ready(device) || welcomeFailed

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Fr3kPalette.SurfaceHigh.copy(alpha = 0.45f), RoundedCornerShape(Fr3kDimens.RadiusRow))
            .border(1.dp, Fr3kPalette.Accent.copy(alpha = 0.45f), RoundedCornerShape(Fr3kDimens.RadiusRow))
            .padding(Fr3kDimens.SpacingMd),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("VOICE", color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

        if (needsSetup) {
            Text("Set up voice:", color = Fr3kPalette.Warn, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            if (nextItem == null) {
                Text("Everything on this device is set up.", color = Fr3kPalette.Ok, fontSize = 11.sp)
            } else {
                Text("${nextItem.title} — ${nextItem.detail}", color = Fr3kPalette.TextDim, fontSize = 11.sp)
                if (nextItem.steps.isNotEmpty()) {
                    nextItem.steps.forEachIndexed { index, step -> Text("  ${index + 1}. $step", color = Fr3kPalette.TextDim, fontSize = 11.sp) }
                }
                when (nextItem.id) {
                    "provider" -> com.mcpintelligence.fr3k.providers.ProviderSetupPanel(
                        service = Fr3kApplication.get().providerSetupService,
                        onSaved = {
                            progress = SetupAndroid.progress(context)
                            walkthrough = emptyList()
                            refresh()
                        },
                    )
                    "voice_tuning" -> {
                        Text("Pick the reply voice in Android's voice settings, then set speed below.", color = Fr3kPalette.TextDim, fontSize = 11.sp)
                        Button(
                            onClick = { openTtsSettings(context) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("CHOOSE ANDROID VOICE") }
                        Text("Speed ${"%.2f".format(rate)}", color = Fr3kPalette.TextDim, fontSize = 11.sp)
                        androidx.compose.material3.Slider(
                            value = rate,
                            onValueChange = { rate = it },
                            valueRange = com.mcpintelligence.fr3k.voice.VoiceTuning.MIN_RATE..com.mcpintelligence.fr3k.voice.VoiceTuning.MAX_RATE,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Pitch ${"%.2f".format(pitch)}", color = Fr3kPalette.TextDim, fontSize = 11.sp)
                        androidx.compose.material3.Slider(
                            value = pitch,
                            onValueChange = { pitch = it },
                            valueRange = com.mcpintelligence.fr3k.voice.VoiceTuning.MIN_PITCH..com.mcpintelligence.fr3k.voice.VoiceTuning.MAX_PITCH,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                com.mcpintelligence.fr3k.voice.VoiceTuning.save(context, rate, pitch)
                                voice.speakWelcome("This is how I will sound.", { })
                                progress = SetupAndroid.progress(context)
                                refresh()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("SAVE AND HEAR IT") }
                    }
                    "microphone" -> Button(
                        onClick = { permission.launch(Manifest.permission.RECORD_AUDIO) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("ALLOW MICROPHONE") }
                    else -> {
                        val intent = SetupAndroid.intentFor(context, nextItem.id)
                        if (intent != null) {
                            Button(
                                onClick = { runCatching { context.startActivity(intent) } },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("OPEN ${nextItem.title.uppercase()}") }
                        }
                    }
                }
                Button(
                    onClick = {
                        SetupAndroid.mark(context, nextItem.id, SetupStatus.DONE)
                        progress = SetupAndroid.progress(context)
                        refresh()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("DONE") }
                if (SetupWalkthrough.canSkip(nextItem)) {
                    Text(
                        "Cannot do this on this device? Skipping is remembered and never asked again.",
                        color = Fr3kPalette.TextDim, fontSize = 10.sp,
                    )
                    Button(
                        onClick = {
                            SetupAndroid.mark(context, nextItem.id, SetupStatus.SKIPPED)
                            progress = SetupAndroid.progress(context)
                            refresh()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("SKIP FOR GOOD") }
                }
            }
            Button(
                onClick = {
                    progress = SetupAndroid.progress(context)
                    refresh()
                    welcomeFailed = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("RECHECK") }
        }

        if (walkthrough.isNotEmpty()) {
            Text("Walkthrough:", color = Fr3kPalette.Warn, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            walkthrough.forEachIndexed { index, step ->
                Text("${index + 1}. ${step.spoken}", color = Fr3kPalette.TextDim, fontSize = 11.sp)
            }
            Button(
                onClick = { voice.speakWelcome(VoiceChecklist.walkthrough(device).orEmpty()) { } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("SAY THE STEPS AGAIN") }
        }

        Text(message, color = if (state == VoiceState.ERROR) Fr3kPalette.Err else Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        lastReply?.let { Text(it, color = Fr3kPalette.TextDim, fontSize = 11.sp) }
        stateText(state)?.let { Text(it, color = Fr3kPalette.Accent, fontSize = 11.sp) }

        Button(
            onClick = {
                muted = !muted
                voice.setMicrophoneMuted(muted)
                if (muted) { voice.stopConversation(); conversing = false; message = "Microphone muted — type instead" }
                else message = "Press start, then speak"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (muted) "UNMUTE MICROPHONE" else "MUTE MICROPHONE") }

        if (muted) {
            // Typed questions still get a spoken answer: mute only stops capture.
            androidx.compose.material3.OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("Type a message") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val text = typed.trim()
                    if (text.isNotEmpty()) { voice.askTyped(text); typed = ""; message = "Asking…" }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("SEND") }
        } else if (conversing) {
            Button(onClick = { voice.stopConversation(); conversing = false }, modifier = Modifier.fillMaxWidth()) { Text("STOP CONVERSATION") }
        } else {
            Button(
                onClick = {
                    val steps = VoiceChecklist.missing(deviceOf(voice.readiness(), context))
                    if (steps.isNotEmpty()) {
                        walkthrough = steps
                        message = "Setup needed before we can talk"
                        // Speak the walkthrough with whatever engine exists; silence is itself a result.
                        voice.speakWelcome(VoiceChecklist.walkthrough(deviceOf(voice.readiness(), context)).orEmpty()) { }
                    } else {
                        voice.startConversation()
                        conversing = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("START CONVERSATION") }
        }
        Text(
            "One press starts the conversation: it listens, answers out loud, then listens again until you stop it.",
            color = Fr3kPalette.TextDim, fontSize = 10.sp,
        )
        Button(onClick = { showHermesSetup = !showHermesSetup }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showHermesSetup) "HIDE OPTIONAL HERMES SETUP" else "HELP INSTALL HERMES IN TERMUX")
        }
        if (showHermesSetup) {
            HermesTermuxSetupPanel(
                provider = selectedSettings.activeAgentProvider,
                model = app.providerSetupService.selectedModel(selectedSettings),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Reads the live device state. Provider readiness is read from the HUD's stored credentials. */
private fun deviceOf(readiness: AndroidVoice.Readiness?, context: Context): VoiceChecklist.Device {
    val app = runCatching { Fr3kApplication.get() }.getOrNull()
    // A key saved through the per-provider Settings panels is a configured provider even though it
    // never wrote the verification stamp; only claim "missing" when nothing is stored at all.
    val provider = app?.selectedProviderReady() == true || app?.providerSetupService?.hasKeyForActive() == true
    return VoiceChecklist.Device(
        microphone = readiness?.microphone ?: micGranted(context),
        // Unknown until the engine answers: reporting false here told operators to install a speech
        // engine they already had.
        tts = readiness?.ttsEngine ?: true,
        recogniser = readiness?.recogniser
            ?: runCatching { android.speech.SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false),
        provider = provider,
    )
}

private fun fix(context: Context, fix: VoiceChecklist.Fix, permission: androidx.activity.result.ActivityResultLauncher<String>) {
    when (fix) {
        VoiceChecklist.Fix.MICROPHONE -> permission.launch(Manifest.permission.RECORD_AUDIO)
        VoiceChecklist.Fix.TTS_SETTINGS -> openTtsSettings(context)
        VoiceChecklist.Fix.SPEECH_INPUT -> openSpeechInputSettings(context)
        VoiceChecklist.Fix.PROVIDER -> openProviderSettings(context)
    }
}

private fun buttonLabel(fix: VoiceChecklist.Fix): String = when (fix) {
    VoiceChecklist.Fix.MICROPHONE -> "ALLOW MICROPHONE"
    VoiceChecklist.Fix.TTS_SETTINGS -> "OPEN ANDROID VOICE SETTINGS"
    VoiceChecklist.Fix.SPEECH_INPUT -> "OPEN SPEECH INPUT SETTINGS"
    VoiceChecklist.Fix.PROVIDER -> "OPEN PROVIDER SETTINGS"
}

private fun stateText(state: VoiceState): String? = when (state) {
    VoiceState.LISTENING -> "● listening"
    VoiceState.THINKING -> "… thinking"
    VoiceState.SPEAKING -> "▸ speaking"
    else -> null
}

/** Greets once per process so the self-test speaks on open, not on every recomposition. */
private object Greeted { var thisProcess = false }

private fun micGranted(context: Context) = androidx.core.content.ContextCompat.checkSelfPermission(
    context, Manifest.permission.RECORD_AUDIO,
) == android.content.pm.PackageManager.PERMISSION_GRANTED

internal fun openTtsSettings(context: Context) {
    val intent = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

internal fun openSpeechInputSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

internal fun openProviderSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(context, com.mcpintelligence.fr3k.ui.settings.SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
