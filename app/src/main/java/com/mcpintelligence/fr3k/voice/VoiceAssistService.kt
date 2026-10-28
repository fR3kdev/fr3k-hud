package com.mcpintelligence.fr3k.voice

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.speech.tts.TextToSpeech
import android.util.Log
import com.mcpintelligence.fr3k.Fr3kApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Shares the HUD's voice with the rest of the FR3K app family.
 *
 * Owns nothing new: it drives the Android speech engine and the existing shared agent session, so
 * a paired app (for example the BLACKWAVE companion) gets the same voice and the same assistant
 * rather than a second, divergent implementation.
 *
 * Callers speak their own question with their own [android.speech.SpeechRecognizer] — they are the
 * foreground app, so the microphone is theirs to hold — and send the transcript here. The answer
 * is spoken by the HUD, which owns the voice output.
 */
class VoiceAssistService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var answering = false

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message -> handle(message); true })

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != VoiceAssistProtocol.ACTION) return null
        return incoming.binder
    }

    override fun onDestroy() {
        scope.cancel()
        tts?.stop(); tts?.shutdown(); tts = null
        super.onDestroy()
    }

    private fun handle(message: Message) {
        if (message.what == VoiceAssistProtocol.MSG_STATUS) { replyStatus(message); return }
        val app = message.data.getString(VoiceAssistProtocol.KEY_CONTEXT) ?: VoiceBriefing.HUD
        when (val request = VoiceAssistProtocol.decode(message.what, message.data.getString(VoiceAssistProtocol.KEY_PROMPT), message.data.getString(VoiceAssistProtocol.KEY_TEXT))) {
            is VoiceAssistProtocol.Request.Ask -> answer(request.prompt, app, message)
            is VoiceAssistProtocol.Request.Speak -> { speak(request.text); reply(message, reply = null, error = null) }
            VoiceAssistProtocol.Request.Stop -> { stopSpeaking(); reply(message, reply = null, error = null) }
            VoiceAssistProtocol.Request.Invalid -> reply(message, reply = null, error = "Invalid request")
        }
    }

    /** Tells a client whether this assistant can actually answer, and why not when it cannot. */
    private fun replyStatus(message: Message) {
        val app = Fr3kApplication.get()
        val settings = app.settings.settings.value
        val provider = !app.secureStore.get(settings.openrouterApiKeyKey).isNullOrBlank() ||
            !app.secureStore.get(settings.opencodegoApiKeyKey).isNullOrBlank()
        val detail = when {
            !provider -> "No model provider key configured in the HUD"
            else -> "Assistant ready"
        }
        val target = message.replyTo ?: return
        val reply = Message.obtain(null, VoiceAssistProtocol.MSG_RESULT).apply {
            data = Bundle().apply {
                putString(VoiceAssistProtocol.KEY_REQUEST_ID, message.data.getString(VoiceAssistProtocol.KEY_REQUEST_ID))
                putBoolean(VoiceAssistProtocol.KEY_READY, provider)
                putString(VoiceAssistProtocol.KEY_DETAIL, detail)
            }
        }
        try { target.send(reply) } catch (_: RemoteException) { }
    }

    private fun answer(prompt: String, app: String, message: Message) {
        if (answering) { reply(message, reply = null, error = "Assistant is busy"); return }
        answering = true
        val session = Fr3kApplication.get().agentSession
        val framed = VoiceBriefing.prompt(prompt, app)
        scope.launch {
            val answerText = try {
                withTimeoutOrNull(90_000) {
                    coroutineScope {
                        // Subscribe before sending: finalReplies has no replay.
                        val pending = async(start = CoroutineStart.UNDISPATCHED) { session.finalReplies.first() }
                        try {
                            session.send(framed)
                            if (pending.isCompleted) pending.await() else null
                        } finally { pending.cancel() }
                    }
                }
            } catch (e: Exception) {
                Log.w("FR3K_HUD", "voice assist ask failed")
                null
            }
            answering = false
            if (answerText.isNullOrBlank()) {
                reply(message, reply = null, error = "No answer. Check the HUD provider settings, or finish any pending tool approval in the HUD.")
            } else {
                speak(answerText)
                reply(message, reply = answerText, error = null)
            }
        }
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null) {
            pendingSpeech = text
            tts = TextToSpeech(applicationContext) { status ->
                main.post {
                    ttsReady = status == TextToSpeech.SUCCESS && (tts?.setLanguage(Locale.getDefault()) ?: -1) >= 0
                    val queued = pendingSpeech; pendingSpeech = null
                    if (ttsReady && queued != null) tts?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "fr3k-assist")
                }
            }
            return
        }
        if (!ttsReady) { pendingSpeech = text; return }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fr3k-assist")
    }

    private fun stopSpeaking() {
        pendingSpeech = null
        tts?.stop()
    }

    private fun reply(request: Message, reply: String?, error: String?) {
        val target = request.replyTo ?: return
        val message = Message.obtain(null, VoiceAssistProtocol.MSG_RESULT).apply {
            data = Bundle().apply {
                putString(VoiceAssistProtocol.KEY_REQUEST_ID, request.data.getString(VoiceAssistProtocol.KEY_REQUEST_ID))
                if (reply != null) putString(VoiceAssistProtocol.KEY_REPLY, reply)
                if (error != null) putString(VoiceAssistProtocol.KEY_ERROR, error)
            }
        }
        try { target.send(message) } catch (_: RemoteException) { }
    }
}
