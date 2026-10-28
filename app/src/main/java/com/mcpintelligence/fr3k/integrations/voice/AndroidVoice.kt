package com.mcpintelligence.fr3k.integrations.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

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
    private var pendingFinished: (() -> Unit)? = null
    @Volatile private var speaking = false
    private var pendingWelcome: String? = null
    private var welcomeResult: ((Boolean) -> Unit)? = null

    /**
     * Speaks a short greeting to prove the whole speech stack works before the operator commits to
     * a conversation: engine present, language data installed, audio route alive. Reports on the
     * main thread. A failure here is exactly what the setup panel needs to show.
     */
    fun speakWelcome(text: String, onResult: (Boolean) -> Unit) {
        mainThread()
        if (closed) { onResult(false); return }
        welcomeResult = onResult
        if (tts != null && ttsReady) {
            if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fr3k-welcome") == TextToSpeech.SUCCESS) return
            welcomeResult = null
            onResult(false)
            return
        }
        pendingWelcome = text
        ensureTts()
    }

    /** True when a speech engine reported ready. */
    val speechReady: Boolean get() = ttsReady

    /** True while this adapter owns the microphone or the spoken reply, so other speech stays quiet. */
    val isActive: Boolean get() = !closed && (conversing || gate.active || speaking || turn?.isActive == true)

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
        if (conversing) stopConversation()
        val current = ++generation
        destroyRecognizer()
        ask(text, current)
    }

    /** Press once: listen, answer, speak, listen again, until [stopConversation]. */
    fun startConversation() {
        mainThread()
        if (closed || conversing) return
        if (!hasMic()) { publish(VoiceState.ERROR, "Microphone permission required"); return }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            publish(VoiceState.ERROR, "No speech recognition service installed")
            return
        }
        conversing = true
        listen()
    }

    fun stopConversation() {
        mainThread()
        if (!conversing) return
        conversing = false
        generation++
        destroyRecognizer()
        turn?.cancel(); turn = null
        tts?.stop()
        speaking = false
        pendingFinished = null
        gate.stop()
        publish(VoiceState.IDLE, "Conversation ended")
    }

    /** Single push-to-talk turn. Returns false when the microphone permission is missing. */
    fun startFromUserGesture(): Boolean {
        mainThread()
        if (closed) return false
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
        if (closed) return
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
                                if (conversing) handler.postDelayed({ if (conversing) listen() }, 600) else gate.stop()
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
            // Subscribe before sending: finalReplies has no replay.
            val answer = async(start = CoroutineStart.UNDISPATCHED) { session.finalReplies.first() }
            val reply = try {
                session.send(com.mcpintelligence.fr3k.voice.VoiceBriefing.prompt(text, appBriefing))
                withTimeoutOrNull(ANSWER_TIMEOUT_MS) { if (answer.isCompleted) answer.await() else null }
            } catch (_: Exception) {
                null
            } finally { answer.cancel() }

            if (current != generation || closed) { gate.stop(); return@launch }
            if (reply.isNullOrBlank()) {
                gate.stop()
                conversing = false
                publish(VoiceState.ERROR, "No answer — check the HUD provider settings")
                return@launch
            }
            speak(reply, current) {
                gate.stop()
                if (conversing && current == generation && !closed) listen()
            }
        }
    }

    private fun speak(text: String, current: Int, onFinished: () -> Unit) {
        if (text.isBlank() || closed || current != generation) return
        pendingFinished = onFinished
        fun play() {
            if (closed || current != generation) { pendingFinished = null; return }
            speaking = true
            publish(VoiceState.SPEAKING, "Speaking…")
            if (!ttsReady || tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fr3k-$current") != TextToSpeech.SUCCESS) {
                speaking = false
                publish(VoiceState.ERROR, "Android text-to-speech unavailable")
                finishSpeech()
            }
        }
        if (tts != null) { play(); return }
        ensureTts()
        play()
    }

    private fun ensureTts(): TextToSpeech {
        tts?.let { return it }
        // The init callback can fire before the constructor returns, so hold the instance.
        val holder = arrayOfNulls<TextToSpeech>(1)
        val created = TextToSpeech(context) { status ->
            val engine = holder[0]
            ttsReady = status == TextToSpeech.SUCCESS && engine != null &&
                engine.setLanguage(Locale.getDefault()) >= 0
            ttsInitFailed = !ttsReady
            engine?.setSpeechRate(com.mcpintelligence.fr3k.voice.VoiceTuning.rate(context))
            engine?.setPitch(com.mcpintelligence.fr3k.voice.VoiceTuning.pitch(context))
            val queued = pendingWelcome
            if (!ttsReady) {
                pendingWelcome = null
                val failure = welcomeResult; welcomeResult = null
                failure?.invoke(false)
            } else if (queued != null) {
                pendingWelcome = null
                if (engine?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "fr3k-welcome") != TextToSpeech.SUCCESS) {
                    val failure = welcomeResult; welcomeResult = null
                    failure?.invoke(false)
                }
            }
        }
        holder[0] = created
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { handler.post { finishSpeech() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { handler.post { finishSpeech() } }
        })
        tts = created
        return created
    }

    private fun finishSpeech() {
        speaking = false
        welcomeResult?.let { it(true) }
        welcomeResult = null
        val callback = pendingFinished
        pendingFinished = null
        callback?.invoke()
    }

    /** Ends capture on release but keeps the transcript still being produced. */
    fun stopListening() {
        mainThread()
        if (closed || conversing) return
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
        generation++
        conversing = false
        runCatching { recognizer?.cancel() }
        destroyRecognizer()
        turn?.cancel(); turn = null
        tts?.stop()
        speaking = false
        pendingFinished = null
        gate.stop()
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

    private companion object { const val ANSWER_TIMEOUT_MS = 90_000L }
}
