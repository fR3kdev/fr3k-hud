package com.mcpintelligence.fr3k.integrations.xiaozhi

import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer

/** One-shot deadline; cancellation may race with execution. */
fun interface VoiceDeadlineScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit
}

private object DefaultVoiceDeadlineScheduler : VoiceDeadlineScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "xiaozhi-handshake-deadline").apply { isDaemon = true }
    }
    override fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
        val future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        return { future.cancel(false); Unit }
    }
}

/** Transport boundary; implementations must not log URLs, headers or server errors. */
interface VoiceTransport {
    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onBinary(bytes: ByteArray) = Unit
        fun onFailure()
    }
    fun connect(url: String, headers: Map<String, String>, listener: Listener)
    fun send(text: String): Boolean
    fun sendBinary(bytes: ByteArray): Boolean = false
    fun close()
}

/** TLS uses platform trust; redirects are forbidden so credentials cannot follow them. */
class OkHttpVoiceTransport internal constructor(private val client: WebSocket.Factory) : VoiceTransport {
    constructor() : this(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build())
    private var socket: WebSocket? = null
    private var closed = false
    private var started = false
    override fun connect(url: String, headers: Map<String, String>, listener: VoiceTransport.Listener) {
        synchronized(this) { if (closed || started) return; started = true }
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val created = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val accepted = synchronized(this@OkHttpVoiceTransport) {
                    if (closed) false else { socket = webSocket; true }
                }
                if (accepted) listener.onOpen() else webSocket.cancel()
            }
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onBinary(bytes.toByteArray())
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onFailure()
        })
        val cancel = synchronized(this) { if (closed) true else { socket = created; false } }
        if (cancel) created.cancel()
    }
    override fun send(text: String): Boolean = synchronized(this) { socket?.send(text) ?: false }
    override fun sendBinary(bytes: ByteArray): Boolean = synchronized(this) { socket?.send(bytes.toByteString()) ?: false }
    override fun close() {
        val previous = synchronized(this) { closed = true; socket.also { socket = null } }
        previous?.cancel()
    }
}

/** Explicit session; callers provide encoded Opus frames and consume events. */
class VoiceSession(
    private val config: Bootstrap.Result.Ready,
    private val deviceId: String,
    private val clientId: String,
    private val transport: VoiceTransport = OkHttpVoiceTransport(),
    private val scheduler: VoiceDeadlineScheduler = DefaultVoiceDeadlineScheduler,
    private val handshakeTimeoutMillis: Long = 10_000,
    private val onEvent: (Event) -> Unit = {}
) {
    init { require(handshakeTimeoutMillis > 0) }
    private var cancelDeadline: (() -> Unit)? = null
    enum class State { IDLE, CONNECTING, AWAITING_HELLO, READY, FAILED, CLOSED }
    enum class ListenMode(val wire: String) { MANUAL("manual"), AUTO("auto"), REALTIME("realtime") }
    sealed class Event {
        class Audio(val payload: ByteArray, val timestamp: Long) : Event()
        class Tts(val state: String, val text: String?) : Event()
        /** Server text that is not session protocol, e.g. "bind this device with code N". */
        class ServerNotice(val text: String) : Event()
    }
    private var sessionId = ""
    private var listening = false
    companion object { const val MAX_AUDIO_BYTES = 65535 }
    @Volatile var state = State.IDLE
        private set
    @Volatile var downlinkSampleRate = 24000
        private set

    fun start(): Boolean {
        val authorization: String
        synchronized(this) {
            if (state != State.IDLE) return false
            val uri = try { URI(config.url) } catch (_: Exception) { return false }
            val token = config.token
            if (uri.scheme != "wss" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null ||
                config.version !in 1..3 || token.isNullOrBlank() ||
                listOf(token, deviceId, clientId).any { it.isBlank() || it.any { c -> c.isISOControl() } }) return false
            authorization = if (' ' in token) token else "Bearer $token"
            state = State.CONNECTING
            try {
                val cancel = scheduler.schedule(handshakeTimeoutMillis) { handshakeExpired() }
                if (state == State.CONNECTING) cancelDeadline = cancel else cancel()
            } catch (_: Exception) { fail(); return false }
        }
        return try {
            transport.connect(config.url, mapOf("Authorization" to authorization, "Protocol-Version" to config.version.toString(), "Device-Id" to deviceId, "Client-Id" to clientId), object : VoiceTransport.Listener {
                override fun onOpen() = opened()
                override fun onText(text: String) = receive(text)
                override fun onBinary(bytes: ByteArray) = receiveBinary(bytes)
                override fun onFailure() = fail()
            })
            state != State.FAILED && state != State.CLOSED
        } catch (_: Exception) { fail(); false }
    }

    @Synchronized private fun opened() {
        if (state != State.CONNECTING) return
        state = State.AWAITING_HELLO
        val hello = buildJsonObject {
            put("type", "hello"); put("version", config.version); put("transport", "websocket")
            put("features", buildJsonObject { put("mcp", false) })
            put("audio_params", buildJsonObject { put("format", "opus"); put("sample_rate", 16000); put("channels", 1); put("frame_duration", 60) })
        }.toString()
        if (!transport.send(hello)) fail()
    }
    @Synchronized private fun receive(text: String) {
        if (state == State.CLOSED || state == State.FAILED) return
        if (text.length > 65536) { fail(); return }
        val root = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
        fun str(key: String): String? = (root?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (state == State.READY) {
            if (str("type") == "tts") {
                val status = str("state")
                if (status in setOf("start", "stop", "sentence_start")) onEvent(Event.Tts(status!!, str("text")))
            }
            return
        }
        if (state != State.AWAITING_HELLO) { fail(); return }
        val version = root?.get("version")
        val validVersion = version == null || ((version as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull == config.version)
        val session = root?.get("session_id")
        if (str("type") != "hello" || str("transport") != "websocket" || !validVersion ||
            (session != null && (str("session_id").isNullOrBlank() || str("session_id")!!.any { it.isISOControl() }))) {
            // A real server answers an unbound device with {"type":"stt","text":"..."} instead of a
            // hello. Surface that instruction verbatim, then fail: the operator needs the real reason.
            val notice = str("text")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 && it.none { c -> c.isISOControl() } }
            if (notice != null) onEvent(Event.ServerNotice(notice))
            fail(); return
        }
        val params = root?.get("audio_params")
        if (params != null) {
            val audio = params as? JsonObject ?: run { fail(); return }
            val format = (audio["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val channels = (audio["channels"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            val rate = (audio["sample_rate"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if ((audio.containsKey("format") && format != "opus") ||
                (audio.containsKey("channels") && channels != 1) ||
                (audio.containsKey("sample_rate") && rate !in setOf(8000, 12000, 16000, 24000, 48000))) { fail(); return }
            if (rate != null) downlinkSampleRate = rate
        }
        sessionId = str("session_id") ?: ""
        state = State.READY
        cancelDeadline?.invoke(); cancelDeadline = null
    }
    @Synchronized fun startListening(mode: ListenMode): Boolean {
        if (state != State.READY || listening) return false
        val text = buildJsonObject { put("session_id", sessionId); put("type", "listen"); put("state", "start"); put("mode", mode.wire) }.toString()
        if (!transport.send(text)) { fail(); return false }
        listening = true
        return true
    }
    @Synchronized fun stopListening(): Boolean {
        if (state != State.READY || !listening) return false
        val text = buildJsonObject { put("session_id", sessionId); put("type", "listen"); put("state", "stop") }.toString()
        if (!transport.send(text)) { fail(); return false }
        listening = false
        return true
    }
    @Synchronized fun sendAudio(payload: ByteArray, timestamp: Long = 0): Boolean {
        if (state != State.READY || !listening || payload.isEmpty() || payload.size > MAX_AUDIO_BYTES || timestamp !in 0..0xffffffffL) return false
        val header = when (config.version) { 2 -> 16; 3 -> 4; else -> 0 }
        val buf = ByteBuffer.allocate(header + payload.size)
        when (config.version) {
            2 -> { buf.putShort(2); buf.putShort(0); buf.putInt(0); buf.putInt(timestamp.toInt()); buf.putInt(payload.size) }
            3 -> { buf.put(0); buf.put(0); buf.putShort(payload.size.toShort()) }
        }
        buf.put(payload)
        if (!transport.sendBinary(buf.array())) { fail(); return false }
        return true
    }
    @Synchronized private fun receiveBinary(bytes: ByteArray) {
        if (state == State.CLOSED || state == State.FAILED) return
        if (state != State.READY) { fail(); return }
        val header = when (config.version) { 2 -> 16; 3 -> 4; else -> 0 }
        if (bytes.size < header || bytes.size > header + MAX_AUDIO_BYTES) { fail(); return }
        val buffer = ByteBuffer.wrap(bytes)
        var timestamp = 0L
        val size = when (config.version) {
            2 -> { buffer.position(8); timestamp = buffer.int.toLong() and 0xffffffffL; buffer.int.toLong() and 0xffffffffL }
            3 -> { buffer.position(2); (buffer.short.toInt() and 0xffff).toLong() }
            else -> bytes.size.toLong()
        }
        if (size == 0L || size != (bytes.size - header).toLong()) { fail(); return }
        onEvent(Event.Audio(bytes.copyOfRange(header, bytes.size), timestamp))
    }
    @Synchronized private fun handshakeExpired() {
        if (state == State.CONNECTING || state == State.AWAITING_HELLO) fail()
    }
    @Synchronized private fun fail() {
        if (state == State.CLOSED || state == State.FAILED) return
        state = State.FAILED
        cancelDeadline?.invoke(); cancelDeadline = null
        transport.close()
    }
    @Synchronized fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        cancelDeadline?.invoke(); cancelDeadline = null
        transport.close()
    }
    override fun toString() = "VoiceSession(state=$state, config=<redacted>)"
}
