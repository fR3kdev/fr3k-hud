package com.mcpintelligence.fr3k.integrations.xiaozhi

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mcpintelligence.fr3k.ui.settings.VoicePhase
import com.mcpintelligence.fr3k.ui.settings.VoiceStatus
import com.mcpintelligence.fr3k.ui.settings.XiaozhiVoiceAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Narrow seams so the adapter state machine is testable without Android or a live server. */
internal interface XiaozhiControllerLink : AutoCloseable {
    val status: XiaozhiController.Status
    val activationCode: String?
    val sessionState: VoiceSession.State
    val downlinkSampleRate: Int
    /** Verbatim server instruction when it refuses the handshake, e.g. "bind with code N". */
    val serverNotice: String? get() = null
    fun discover(consented: Boolean)
    fun startListening(): Boolean
    fun stopListening(): Boolean
    fun sendAudio(packet: ByteArray): Boolean
}

internal interface XiaozhiAudioLink : AutoCloseable {
    fun startPlayback()
    fun startCapture()
    fun stopCapture()
    fun enqueue(packet: ByteArray)
}

/** Spoken output. Android system TTS is the default; Xiaozhi server audio is opt-in. */
internal interface XiaozhiSpeaker {
    fun speak(text: String)
    fun stop()
    fun shutdown()
}

/**
 * Binds the real Xiaozhi transport to the voice UI.
 *
 * Output default: the reply is spoken by Android text-to-speech from the `tts` text events.
 * Opt-in: the server's own Opus audio is played instead. Exactly one path is active, so a reply
 * is never played twice.
 *
 * Every public member must run on the main thread. Consent is enforced by the caller: the UI only
 * calls [connect] after the operator ticked the consent box in the same screen visit.
 */
internal class XiaozhiVoiceAdapterImpl(
    private val makeController: ((XiaozhiController.Status, String?) -> Unit, (VoiceSession.Event) -> Unit) -> XiaozhiControllerLink,
    private val makeAudio: (Int, (ByteArray) -> Boolean, (Throwable) -> Unit) -> XiaozhiAudioLink,
    private val speaker: XiaozhiSpeaker,
    private val isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
    private val post: (() -> Unit) -> Unit = { block -> Handler(Looper.getMainLooper()).post(block) },
) : XiaozhiVoiceAdapter {

    private val mutableStatus = MutableStateFlow(VoiceStatus(VoicePhase.IDLE))
    override val status: StateFlow<VoiceStatus> = mutableStatus.asStateFlow()

    @Volatile private var androidTtsOutput = true
    private var controller: XiaozhiControllerLink? = null
    private var audio: XiaozhiAudioLink? = null
    private var closed = false
    private var talking = false
    private var speaking = false

    /** True while replies are spoken by Android TTS; false while server audio is played. */
    override val speaksWithAndroidTts: Boolean get() = androidTtsOutput

    override fun setAndroidTtsOutput(enabled: Boolean) {
        if (androidTtsOutput == enabled) return
        androidTtsOutput = enabled
        if (!enabled) { speaker.stop(); speaking = false }
        if (!enabled) audio?.let { runCatching { it.startPlayback() } }
    }

    private fun onMain(block: () -> Unit) {
        if (isMainThread()) block() else post(block)
    }

    private fun publish(phase: VoicePhase, message: String = "", code: String? = null) {
        onMain {
            if (closed) return@onMain
            mutableStatus.value = VoiceStatus(phase, message, code)
        }
    }

    override fun connect() {
        checkMain()
        if (closed) return
        val link = controller ?: makeController({ status, code -> controllerStatus(status, code) }, ::sessionEvent).also { controller = it }
        publish(VoicePhase.CONNECTING, "Requesting connection data")
        link.discover(true)
    }

    private fun controllerStatus(status: XiaozhiController.Status, code: String?) = onMain {
        if (closed) return@onMain
        when (status) {
            XiaozhiController.Status.IDLE -> publish(VoicePhase.IDLE)
            XiaozhiController.Status.DISCOVERING -> publish(VoicePhase.CONNECTING, "Discovering")
            XiaozhiController.Status.ACTIVATION_REQUIRED -> publish(
                VoicePhase.ACTIVATION_PENDING,
                controller?.serverNotice
                    ?: if (code == null) "Activation pending on the server" else "Enter this code in the server console, then reconnect",
                code
            )
            XiaozhiController.Status.CONNECTING -> publish(VoicePhase.CONNECTING, "Opening voice channel")
            XiaozhiController.Status.READY -> openAudio()
            XiaozhiController.Status.ERROR -> {
                stopTalkingInternal()
                // Drop the dead audio engine so a retry rebuilds it at the new negotiated rate.
                val dead = audio; audio = null
                runCatching { dead?.close() }
                publish(VoicePhase.ERROR, "Voice channel failed; retry when ready")
            }
            XiaozhiController.Status.CLOSED -> publish(VoicePhase.IDLE)
        }
    }

    private fun openAudio() {
        if (closed) return
        val rate = controller?.downlinkSampleRate ?: 24000
        if (audio == null) {
            val created = makeAudio(rate, { packet -> controller?.sendAudio(packet) ?: false }, ::audioFailure)
            audio = created
            created.startPlayback()
        }
        publish(VoicePhase.READY, "Channel ready")
    }

    private fun sessionEvent(event: VoiceSession.Event) {
        when (event) {
            is VoiceSession.Event.Tts -> onMain {
                if (closed) return@onMain
                when (event.state) {
                    "sentence_start" -> {
                        val text = event.text?.trim().orEmpty()
                        if (androidTtsOutput && text.isNotEmpty()) {
                            speaking = true
                            publish(VoicePhase.SPEAKING, "Speaking reply")
                            speaker.speak(text)
                        }
                    }
                    "stop" -> { speaking = false; if (talking) publish(VoicePhase.LISTENING) else publish(VoicePhase.READY) }
                }
            }
            is VoiceSession.Event.Audio -> if (!androidTtsOutput) audio?.enqueue(event.payload)
            // The server's own words when it refuses the handshake (e.g. "bind with code N").
            is VoiceSession.Event.ServerNotice -> onMain {
                if (closed) return@onMain
                publish(VoicePhase.ACTIVATION_PENDING, event.text)
            }
        }
    }

    private fun audioFailure(error: Throwable) = onMain {
        if (closed) return@onMain
        talking = false
        publish(VoicePhase.ERROR, "Audio stopped: ${error.message ?: "device error"}; reconnect")
    }

    override fun startTalking() {
        checkMain()
        if (closed || talking) return
        val phase = mutableStatus.value.phase
        if (!androidTtsOutput && phase != VoicePhase.READY) return
        val link = controller ?: return
        if (!link.startListening()) { publish(VoicePhase.ERROR, "Voice channel is not ready"); return }
        audio?.let {
            try { it.startCapture() } catch (e: Exception) {
                link.stopListening()
                publish(VoicePhase.ERROR, "Microphone unavailable: ${e.message}")
                return
            }
            it.startPlayback()
        }
        talking = true
        publish(VoicePhase.LISTENING, "Listening")
    }

    override fun stopTalking() {
        checkMain()
        stopTalkingInternal()
    }

    private fun stopTalkingInternal() {
        audio?.let { runCatching { it.stopCapture() } }
        if (talking) { controller?.let { runCatching { it.stopListening() } }; talking = false }
        if (!closed && mutableStatus.value.phase in setOf(VoicePhase.LISTENING, VoicePhase.SPEAKING)) {
            publish(VoicePhase.READY)
        }
    }

    private fun checkMain() = check(isMainThread()) { "Voice calls must run on the main thread" }

    override fun disconnect() {
        checkMain()
        if (closed) return
        stopTalkingInternal()
        val engine = audio; audio = null
        runCatching { engine?.close() }
        val link = controller; controller = null
        runCatching { link?.close() }
        speaker.stop()
        mutableStatus.value = VoiceStatus(VoicePhase.IDLE)
    }

    override fun close() {
        checkMain()
        if (closed) return
        disconnect()
        closed = true
        speaker.shutdown()
    }
}

/** Android TextToSpeech implementation of [XiaozhiSpeaker]; all calls are main-thread. */
internal class AndroidXiaozhiSpeaker(
    context: Context,
    private val onSpoken: () -> Unit = {},
) : XiaozhiSpeaker {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    private fun ensure(): TextToSpeech {
        tts?.let { return it }
        // The init callback can fire before the constructor returns, so hold the instance out-of-band.
        val holder = arrayOfNulls<TextToSpeech>(1)
        val created = TextToSpeech(appContext) { status ->
            val engine = holder[0]
            ready = status == TextToSpeech.SUCCESS && engine != null && engine.setLanguage(Locale.getDefault()) >= 0
            if (ready) engine?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val text = pending; pending = null
            if (ready && text != null) engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fr3k-voice")
        }
        holder[0] = created
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = onSpoken()
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) = onSpoken()
        })
        tts = created
        return created
    }

    override fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val engine = ensure()
        if (!ready) { pending = clean; return }
        engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "fr3k-voice")
    }

    override fun stop() { tts?.stop() }

    override fun shutdown() {
        pending = null
        ready = false
        tts?.stop(); tts?.shutdown(); tts = null
    }
}

/** Production adapter: real controller, real audio engine, Android TTS default output. */
internal fun realXiaozhiVoiceAdapter(context: Context): XiaozhiVoiceAdapter {
    val identity = XiaozhiController.persistentIdentity(context)
    return XiaozhiVoiceAdapterImpl(
        makeController = { onState, onEvent ->
            val controller = XiaozhiController(
                identity = identity,
                onState = { status, code -> onState(status, code) },
                onEvent = onEvent,
            )
            object : XiaozhiControllerLink {
                override val status get() = controller.status
                override val activationCode get() = controller.activationCode
                override val sessionState get() = controller.session?.state ?: VoiceSession.State.IDLE
                override val downlinkSampleRate get() = controller.session?.downlinkSampleRate ?: 24000
                override val serverNotice get() = controller.serverNotice
                override fun discover(consented: Boolean) = controller.discover(consented)
                override fun startListening(): Boolean = controller.session?.startListening(VoiceSession.ListenMode.MANUAL) == true
                override fun stopListening(): Boolean = controller.session?.stopListening() == true
                override fun sendAudio(packet: ByteArray): Boolean = controller.session?.sendAudio(packet) == true
                override fun close() = controller.close()
            }
        },
        makeAudio = { rate, onPacket, onError ->
            val engine = XiaozhiAudio(context, rate, onPacket, onError)
            object : XiaozhiAudioLink {
                override fun startPlayback() = engine.startPlayback()
                override fun startCapture() = engine.startCapture()
                override fun stopCapture() = engine.stopCapture()
                override fun enqueue(packet: ByteArray) { engine.enqueuePlayback(packet) }
                override fun close() = engine.close()
            }
        },
        speaker = AndroidXiaozhiSpeaker(context),
    )
}
