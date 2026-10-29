package com.mcpintelligence.fr3k.integrations.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.mcpintelligence.fr3k.core.tools.SharedAgentSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.Locale

internal class VoiceTurnGate {
    enum class Start { Started, PermissionRequired, Busy }
    var active = false
        private set
    fun start(permission: Boolean, busy: Boolean): Start {
        if (!permission) return Start.PermissionRequired
        if (active || busy) return Start.Busy
        active = true
        return Start.Started
    }
    fun stop() { active = false }
}

internal class VoiceSpeechCompletion {
    var activeId: String? = null
        private set
    private var callback: ((Boolean) -> Unit)? = null
    fun begin(id: String, onResult: (Boolean) -> Unit) {
        activeId = id
        callback = onResult
    }
    fun clear() { activeId = null; callback = null }
    fun finish(id: String?, success: Boolean): Boolean {
        if (id == null || id != activeId) return false
        val result = callback
        clear()
        result?.invoke(success)
        return true
    }
}

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

/** Subscribe before send (no replay), with one deadline covering provider/tool execution too. */
internal suspend fun awaitVoiceReply(
    replies: kotlinx.coroutines.flow.Flow<String>,
    timeoutMs: Long,
    send: suspend () -> Unit,
): String? = withTimeoutOrNull(timeoutMs) {
    coroutineScope {
        val answer = async(start = CoroutineStart.UNDISPATCHED) { replies.first() }
        try {
            send()
            answer.await()
        } finally { answer.cancel() }
    }
}

/**
 * Android-native voice assistant over the shared HUD agent session.
 *
 * [startConversation] is the mode the operator actually wants: press once, then listen → answer →
 * speak → listen again until [stopConversation]. [startFromUserGesture] remains for a single turn.
 *
 * All public methods must run on the main thread. Releasing a button calls [stopListening], which
 * ends capture but keeps the pending transcript live — cancelling there discarded the answer and
 * left the UI stuck on "Wait".
 */
class AndroidVoice(
    context: Context,
    private val session: SharedAgentSession,
    private val onState: (VoiceState, String) -> Unit = { _, _ -> },
    /** Which app's briefing grounds the answer; see [com.mcpintelligence.fr3k.voice.VoiceBriefing]. */
    private val appBriefing: String = com.mcpintelligence.fr3k.voice.VoiceBriefing.HUD,
) : AutoCloseable {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val gate = VoiceTurnGate()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitFailed = false
    private var closed = false
    private var turn: Job? = null
    private var generation = 0
    private var conversing = false
    private val completion = VoiceSpeechCompletion()
    private var utteranceSerial = 0
    private var pendingPlayback: (() -> Unit)? = null
    @Volatile private var speaking = false
    private var speechStarted = false
    private var startWatchdog: (() -> Unit)? = null
    private var microphoneMuted = false
    private var welcomeResult: ((Boolean) -> Unit)? = null

    /**
     * Speaks a short greeting to prove the whole speech stack works before the operator commits to
     * a conversation: engine present, language data installed, audio route alive. Reports on the
     * main thread. A failure here is exactly what the setup panel needs to show.
     */
    fun speakWelcome(text: String, onResult: (Boolean) -> Unit) {
        mainThread()
        if (closed) { onResult(false); return }
        stopConversation()
        val current = generation
        welcomeResult = onResult
        speak(text, current) { success ->
            val result = welcomeResult
            welcomeResult = null
            result?.invoke(success)
        }
    }

    /** True when a speech engine reported ready. */
    val speechReady: Boolean get() = ttsReady

    /** True while this adapter owns the microphone or the spoken reply, so other speech stays quiet. */
    val isActive: Boolean get() = !closed && (conversing || gate.active || speaking || completion.activeId != null || turn?.isActive == true)

    val inConversation: Boolean get() = conversing

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    private fun publish(state: VoiceState, message: String) { if (!closed) onState(state, message) }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Installed-device readiness, checked without capturing or speaking. */
    data class Readiness(val recogniser: Boolean, val ttsEngine: Boolean, val microphone: Boolean) {
        val ready: Boolean get() = recogniser && ttsEngine && microphone
    }

    fun readiness(): Readiness {
        mainThread()
        val recogniser = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        ensureTts()
        // The engine initialises asynchronously. Reporting "missing" before it answers makes the
        // setup panel nag on a device where speech already works, so only a real failure counts.
        return Readiness(recogniser, ttsReady || !ttsInitFailed, hasMic())
    }

    /** One typed question, spoken back. Used when the microphone is muted. */
    fun askTyped(text: String) {
        mainThread()
        if (closed || text.isBlank()) return
        stopConversation()
        val current = ++generation
        destroyRecognizer()
        ask(text, current)
    }

    /** Press once: listen, answer, speak, listen again, until [stopConversation]. */
    fun startConversation() {
        mainThread()
        if (closed || conversing || microphoneMuted) return
        if (!hasMic()) { publish(VoiceState.ERROR, "Microphone permission required"); return }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            publish(VoiceState.ERROR, "No speech recognition service installed")
            return
        }
        stopConversation()
        conversing = true
        listen()
    }

    fun stopConversation() {
        mainThread()
        conversing = false
        generation++
        destroyRecognizer()
        turn?.cancel(); turn = null
        completion.clear()
        pendingPlayback = null
        tts?.stop()
        speaking = false
        gate.stop()
        val result = welcomeResult
        welcomeResult = null
        result?.invoke(false)
        publish(VoiceState.IDLE, "Conversation ended")
    }

    /** Muting is unconditional teardown, even after an error; typed replies remain available.
     * Unmuting never records automatically: the operator must tap to start again.
     */
    fun setMicrophoneMuted(muted: Boolean) {
        mainThread()
        if (closed) return
        microphoneMuted = muted
        if (muted) stopConversation()
    }

    /** Single push-to-talk turn. Returns false when the microphone permission is missing. */
    fun startFromUserGesture(): Boolean {
        mainThread()
        if (closed || microphoneMuted) return false
        if (!hasMic()) { publish(VoiceState.ERROR, "Microphone permission required"); return false }
        if (conversing) return true
        when (gate.start(true, session.busy.value)) {
            VoiceTurnGate.Start.Busy -> { publish(VoiceState.THINKING, "Assistant is busy"); return true }
            else -> Unit
        }
        listen()
        return true
    }

    private fun listen() {
        if (closed || microphoneMuted) return
        val current = ++generation
        destroyRecognizer()
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { speech ->
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onError(error: Int) {
                        if (current != generation) return
                        destroyRecognizer()
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                if (conversing) handler.postDelayed({ if (conversing && current == generation) listen() }, 350)
                                else { gate.stop(); publish(VoiceState.IDLE, "No speech recognised") }
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                conversing = false; gate.stop()
                                publish(VoiceState.ERROR, "Microphone permission denied")
                            }
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                if (conversing) handler.postDelayed({ if (conversing && current == generation) listen() }, 600) else gate.stop()
                            SpeechRecognizer.ERROR_CLIENT ->
                                if (!conversing) gate.stop() // expected after stopListening()
                            else -> {
                                conversing = false; gate.stop()
                                publish(VoiceState.ERROR, "Speech recognition error $error")
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        if (current != generation) return
                        destroyRecognizer()
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        if (text.isEmpty()) {
                            if (conversing) listen() else { gate.stop(); publish(VoiceState.IDLE, "No speech recognised") }
                            return
                        }
                        ask(text, current)
                    }
                })
                speech.startListening(recognizerIntent())
            }
            publish(VoiceState.LISTENING, if (conversing) "Listening — speak now" else "Listening")
        } catch (e: Exception) {
            destroyRecognizer()
            conversing = false
            gate.stop()
            publish(VoiceState.ERROR, "Microphone unavailable: ${e.message}")
        }
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    }

    private fun ask(text: String, current: Int) {
        publish(VoiceState.THINKING, "Thinking…")
        turn = scope.launch {
            val reply = try {
                awaitVoiceReply(session.finalReplies, ANSWER_TIMEOUT_MS) {
                    session.send(com.mcpintelligence.fr3k.voice.VoiceBriefing.prompt(text, appBriefing))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (current != generation || closed) return@launch
            if (reply.isNullOrBlank()) {
                gate.stop()
                conversing = false
                publish(VoiceState.ERROR, "No answer — check the HUD provider settings")
                return@launch
            }
            speak(reply, current) { success ->
                if (current == generation && !closed) {
                    gate.stop()
                    if (!success) {
                        conversing = false
                        publish(VoiceState.ERROR, "Android text-to-speech unavailable")
                    } else if (conversing) listen()
                    else publish(VoiceState.IDLE, "Reply spoken")
                }
            }
        }
    }

    private fun speak(text: String, current: Int, onFinished: (Boolean) -> Unit) {
        if (text.isBlank() || closed || current != generation) { onFinished(false); return }
        val id = "fr3k-${++utteranceSerial}"
        completion.begin(id, onFinished)
        fun play() {
            if (closed || current != generation || completion.activeId != id) return
            pendingPlayback = null
            if (!ttsReady) { finishSpeech(id, false); return }
            applyTuning()
            speaking = true
            speechStarted = false
            publish(VoiceState.SPEAKING, "Speaking…")
            if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                finishSpeech(id, false)
                return
            }
            // An engine can accept the utterance and never start it. Without this the UI would sit on
            // "Speaking…" forever with no audio, which reads as a silent success.
            startWatchdog?.invoke()
            val watchdog = Runnable {
                if (completion.activeId == id && !speechStarted) finishSpeech(id, false)
            }
            handler.postDelayed(watchdog, SPEECH_START_TIMEOUT_MS)
            startWatchdog = { handler.removeCallbacks(watchdog) }
        }
        ensureTts()
        if (ttsReady || ttsInitFailed) play()
        else pendingPlayback = ::play
    }

    /** Refresh an already initialized engine after the operator changes tuning. */
    fun applyTuning() {
        mainThread()
        if (closed) return
        tts?.setSpeechRate(com.mcpintelligence.fr3k.voice.VoiceTuning.rate(context))
        tts?.setPitch(com.mcpintelligence.fr3k.voice.VoiceTuning.pitch(context))
    }

    private fun ensureTts(): TextToSpeech {
        tts?.let { return it }
        // Always dispatch initialization: constructors may invoke their callback before returning.
        // Application context: an engine tied to an Activity dies with it and then reports neither
        // start nor error, leaving the UI stuck on "Speaking…" with no audio.
        val created = TextToSpeech(context.applicationContext) { status ->
            handler.post {
                if (!closed) {
                    val engine = tts
                    ttsReady = status == TextToSpeech.SUCCESS && engine != null &&
                        engine.setLanguage(Locale.getDefault()) >= 0
                    ttsInitFailed = !ttsReady
                    applyTuning()
                    val queued = pendingPlayback
                    pendingPlayback = null
                    queued?.invoke()
                }
            }
        }
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { handler.post { speechStarted = true; startWatchdog?.invoke() } }
            override fun onDone(utteranceId: String?) { handler.post { finishSpeech(utteranceId, true) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { handler.post { finishSpeech(utteranceId, false) } }
            override fun onError(utteranceId: String?, errorCode: Int) { handler.post { finishSpeech(utteranceId, false) } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { handler.post { finishSpeech(utteranceId, false) } }
        })
        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts = created
        return created
    }

    private fun finishSpeech(utteranceId: String?, success: Boolean) {
        if (utteranceId == null || utteranceId != completion.activeId) return
        startWatchdog?.invoke()
        startWatchdog = null
        speaking = false
        pendingPlayback = null
        completion.finish(utteranceId, success)
    }

    /** Ends capture on release but keeps the transcript still being produced. */
    fun stopListening() {
        mainThread()
        if (closed || conversing || microphoneMuted) return
        if (recognizer != null) {
            runCatching { recognizer?.stopListening() }
            publish(VoiceState.THINKING, "Processing…")
        }
    }

    /** Permission grant never starts recording automatically: ask for another tap. */
    fun onPermissionResult(granted: Boolean) {
        mainThread()
        if (!closed) publish(VoiceState.IDLE, if (granted) "Microphone enabled; tap to speak" else "Microphone permission denied")
    }

    private fun destroyRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    /** Cancels the current turn and speech, leaving nothing running. */
    fun cancel() {
        mainThread()
        stopConversation()
        publish(VoiceState.IDLE, "Stopped")
    }

    override fun close() {
        mainThread()
        if (closed) return
        cancel()
        closed = true
        tts?.shutdown(); tts = null
        scope.cancel()
    }

    private companion object {
        const val ANSWER_TIMEOUT_MS = 90_000L
        const val SPEECH_START_TIMEOUT_MS = 1_500L
    }
}
