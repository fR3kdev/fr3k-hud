package com.mcpintelligence.fr3k.voice

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mcpintelligence.fr3k.Fr3kApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.UUID

/** Shared HUD agent and speech output; signature permission enforced by the manifest. */
class VoiceAssistService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var initialized = CompletableDeferred<Boolean>()
    private var job: Job? = null
    private var active: Message? = null
    private val completion = VoiceAssistProtocol.SpeechCompletion()
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { handle(Message.obtain(it)); true })
    private val approvalPrompts = mutableMapOf<String, Long>()
    private val pendingCallers = java.util.ArrayDeque<Int>()
    @Volatile private var callerUid: Int = -1
    /** Captures the real caller before Messenger hands the message to the main-thread handler. */
    private val gated = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val uid = Binder.getCallingUid()
            callerUid = uid
            synchronized(pendingCallers) { pendingCallers.addLast(uid) }
            // transact(), not onTransact(): the latter is protected. The parcel is still at position 0
            // because nothing was read, so it forwards unchanged.
            return incoming.binder.transact(code, data, reply, flags)
        }
    }
    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == VoiceAssistProtocol.ACTION) gated else null

    /**
     * Signature permission already limits callers to this signing key; this is the operator's own
     * consent on top of it. Unapproved callers are refused *and* shown a prompt, so a companion can
     * never start capturing or speaking silently.
     */
    private fun approvedCaller(): Boolean {
        val uid = synchronized(pendingCallers) { pendingCallers.pollFirst() ?: callerUid }
        // In-process callers (the HUD's own UI) are always the operator.
        if (uid <= 0 || uid == Process.myUid()) return true
        val packageName = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: return false
        val certificate = ConnectionApproval.certificateSha256(this, packageName) ?: return false
        if (ConnectionApproval.isApproved(this, packageName, certificate)) return true
        requestApproval(packageName, certificate)
        return false
    }

    private fun requestApproval(packageName: String, certificate: String) {
        val now = System.currentTimeMillis()
        val last = approvalPrompts[packageName] ?: 0L
        if (now - last < 30_000) return
        approvalPrompts[packageName] = now
        val label = ConnectionApproval.label(this, packageName)
        val prompt = ConnectionApprovalActivity.intent(this, packageName, label, certificate, "drive voice and ask the shared agent")
        val shown = runCatching { startActivity(prompt) }.isSuccess
        if (!shown) notifyApproval(packageName, label, prompt)
    }

    private fun notifyApproval(packageName: String, label: String, prompt: Intent) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Connection requests", NotificationManager.IMPORTANCE_HIGH)
        )
        val pending = PendingIntent.getActivity(this, packageName.hashCode(), prompt, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("$label wants to use FR3K HUD voice")
            .setContentText("Tap to allow or deny the connection.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(packageName.hashCode(), notification) }
    }

    private companion object { const val CHANNEL = "fr3k-connection-approvals" }

    private suspend fun speechReady(): Boolean {
        if (tts == null) {
            tts = TextToSpeech(applicationContext) { result -> main.post {
                val engine = tts
                val ready = result == TextToSpeech.SUCCESS && engine != null && engine.setLanguage(Locale.getDefault()) >= 0
                if (ready) {
                    engine?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    engine?.setSpeechRate(VoiceTuning.rate(applicationContext))
                    engine?.setPitch(VoiceTuning.pitch(applicationContext))
                    engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit
                        override fun onDone(id: String?) { main.post { finishSpeech(id, null) } }
                        @Deprecated("Platform callback")
                        override fun onError(id: String?) { main.post { finishSpeech(id, "HUD speech engine failed") } }
                        override fun onError(id: String?, errorCode: Int) { main.post { finishSpeech(id, "HUD speech engine error $errorCode") } }
                        override fun onStop(id: String?, interrupted: Boolean) { main.post { finishSpeech(id, "HUD speech interrupted") } }
                    })
                }
                initialized.complete(ready)
            } }
        }
        return withTimeoutOrNull(12_000) { initialized.await() } == true
    }
    private fun providerError(): String? {
        val app = Fr3kApplication.get()
        val settings = app.settings.settings.value
        val key = when (settings.activeAgentProvider) {
            "openrouter" -> settings.openrouterApiKeyKey
            "opencode-go" -> settings.opencodegoApiKeyKey
            "groq" -> settings.groqApiKeyKey
            else -> return "Unsupported active HUD provider"
        }
        return if (app.secureStore.get(key).isNullOrBlank()) "No key for selected HUD provider: ${settings.activeAgentProvider}" else null
    }
    private fun handle(message: Message) {
        if (!approvedCaller()) {
            // The prompt has been shown (or throttled); nothing is captured, asked or spoken.
            error(message, "FR3K HUD is waiting for the operator to approve this connection")
            return
        }
        if (message.what == VoiceAssistProtocol.MSG_STATUS) {
            scope.launch {
                val error = providerError() ?: if (!speechReady()) "HUD speech engine unavailable or language not installed" else null
                send(message, 100, Bundle().apply {
                    putBoolean("ready", error == null); putString("detail", error ?: "Selected provider configured; speech engine ready")
                    putInt("version", VoiceAssistProtocol.VERSION)
                })
            }
            return
        }
        when (val request = VoiceAssistProtocol.decode(message.what, message.data.getString("prompt"), message.data.getString("text"))) {
            VoiceAssistProtocol.Request.Stop -> { cancelActive(); send(message, 100) }
            VoiceAssistProtocol.Request.Invalid -> error(message, "Invalid voice request")
            else -> {
                if (active != null) { error(message, "HUD assistant is busy"); return }
                active = message
                job = scope.launch {
                    try {
                        val providerFailure = if (request is VoiceAssistProtocol.Request.Ask) providerError() else null
                        if (providerFailure != null) { error(message, providerFailure); active = null; return@launch }
                        if (!speechReady()) { error(message, "HUD speech engine unavailable or language not installed"); active = null; return@launch }
                        val text = when (request) {
                            is VoiceAssistProtocol.Request.Speak -> request.text
                            is VoiceAssistProtocol.Request.Ask -> {
                                val session = Fr3kApplication.get().agentSession
                                withTimeoutOrNull(90_000) {
                                    coroutineScope {
                                        val reply = async(start = CoroutineStart.UNDISPATCHED) { session.finalReplies.first() }
                                        try {
                                            session.send(VoiceBriefing.prompt(request.prompt, message.data.getString("context") ?: VoiceBriefing.HUD))
                                            if (reply.isCompleted) reply.await() else null
                                        } finally { reply.cancel() }
                                    }
                                }
                            }
                            else -> null
                        }
                        if (text.isNullOrBlank()) { error(message, "No answer. Check provider settings or pending HUD tool approval."); active = null; return@launch }
                        send(message, 100, Bundle().apply { putString("reply", text) })
                        val id = UUID.randomUUID().toString()
                        completion.begin(id)
                        // Do not truncate a provider answer or synthesize a completion event.
                        if (text.length > TextToSpeech.getMaxSpeechInputLength()) {
                            finishSpeech(id, "HUD answer exceeds speech engine input limit")
                        } else if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                            finishSpeech(id, "HUD speech engine rejected the reply")
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error(message, "HUD assistant request failed"); active = null }
                }
            }
        }
    }
    private fun finishSpeech(id: String?, error: String?) {
        if (id == null || !completion.finish(id)) return
        val request = active ?: return
        active = null
        send(request, 101, Bundle().apply { if (error != null) putString("error", error) })
    }
    private fun cancelActive() {
        job?.cancel(); job = null
        completion.cancel()
        val previous = active; active = null
        tts?.stop()
        previous?.let { error(it, "HUD voice request cancelled"); send(it, 101, Bundle().apply { putString("error", "HUD speech cancelled") }) }
    }
    private fun error(request: Message, reason: String) = send(request, 100, Bundle().apply { putString("error", reason) })
    private fun send(request: Message, what: Int, payload: Bundle = Bundle()) {
        payload.putString("requestId", request.data.getString("requestId"))
        try { request.replyTo?.send(Message.obtain(null, what).apply { data = payload }) } catch (_: RemoteException) { }
    }
    override fun onDestroy() {
        cancelActive(); scope.cancel(); tts?.shutdown(); tts = null
        super.onDestroy()
    }
}
