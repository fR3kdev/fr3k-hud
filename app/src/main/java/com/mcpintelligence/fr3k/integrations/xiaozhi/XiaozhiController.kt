package com.mcpintelligence.fr3k.integrations.xiaozhi

import android.content.Context
import com.mcpintelligence.fr3k.hud.BuildConfig
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicitly user-initiated discovery. The Android enrollment contract is unverified. */
class XiaozhiController(
    private val identity: () -> Identity,
    private val discover: (String, String, String) -> Bootstrap.Result = { endpoint, device, client ->
        BootstrapClient().discover(endpoint, device, client, BootstrapClient.androidSystemInfo(device, "FR3K HUD", BuildConfig.VERSION_NAME))
    },
    private val makeSession: (Bootstrap.Result.Ready, Identity, (VoiceSession.Event) -> Unit) -> VoiceSession = { config, id, events ->
        VoiceSession(config, id.deviceId, id.clientId, onEvent = events)
    },
    private val onState: (Status, String?) -> Unit = { _, _ -> },
    private val onEvent: (VoiceSession.Event) -> Unit = {}
) : AutoCloseable {
    class Identity(val deviceId: String, val clientId: String) {
        override fun toString() = "Identity(virtual, redacted)"
    }
    enum class Status { IDLE, DISCOVERING, ACTIVATION_REQUIRED, CONNECTING, READY, ERROR, CLOSED }
    companion object {
        const val ENDPOINT = "https://voice.fr3k.dev/xiaozhi/ota/"
        const val COMPATIBILITY = "Android virtual identity enrollment compatibility is unverified"
        /** App-private persistent virtual identity, not a hardware MAC address. */
        fun persistentIdentity(context: Context): () -> Identity = {
            synchronized(XiaozhiController::class.java) {
                val prefs = context.applicationContext.getSharedPreferences("xiaozhi_virtual_identity", Context.MODE_PRIVATE)
                var device = prefs.getString("device", null)
                var client = prefs.getString("client", null)
                if (device == null || client == null) {
                    val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
                    bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
                    device = bytes.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
                    client = UUID.randomUUID().toString()
                    check(prefs.edit().putString("device", device).putString("client", client).commit())
                }
                Identity(device, client)
            }
        }
    }
    @Volatile var status = Status.IDLE
        private set
    @Volatile var activationCode: String? = null
        private set
    @Volatile var session: VoiceSession? = null
        private set
    /** Verbatim server text from the last rejected handshake, e.g. the binding instruction. */
    @Volatile var serverNotice: String? = null
        private set
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "xiaozhi-controller").apply { isDaemon = true } }
    private var generation = 0

    /** Run only after a transparent consent prompt; false does nothing. Nonblocking. */
    @Synchronized fun discover(consented: Boolean) {
        if (!consented || status == Status.CLOSED || status == Status.DISCOVERING) return
        val run = ++generation
        session?.close(); session = null; activationCode = null; serverNotice = null
        publish(Status.DISCOVERING)
        worker.execute {
            val id: Identity
            val result: Bootstrap.Result
            try { id = identity(); result = discover(ENDPOINT, id.deviceId, id.clientId) }
            catch (_: Exception) { synchronized(this) { if (generation == run && status != Status.CLOSED) publish(Status.ERROR) }; return@execute }
            synchronized(this) {
                if (generation != run || status == Status.CLOSED) return@synchronized
                when (result) {
                    is Bootstrap.Result.ActivationCode -> { activationCode = result.code; publish(Status.ACTIVATION_REQUIRED) }
                    Bootstrap.Result.ActivationPending -> publish(Status.ACTIVATION_REQUIRED)
                    Bootstrap.Result.Invalid -> publish(Status.ERROR)
                    is Bootstrap.Result.Ready -> {
                        val created = try { makeSession(result, id) { event ->
                            if (event is VoiceSession.Event.ServerNotice) serverNotice = event.text
                            if (generation == run && status != Status.CLOSED) onEvent(event)
                        } }
                        catch (_: Exception) { publish(Status.ERROR); return@synchronized }
                        session = created
                        publish(Status.CONNECTING)
                        if (!created.start()) publish(Status.ERROR) else watch(run, created)
                    }
                }
            }
        }
    }
    private fun watch(run: Int, current: VoiceSession) {
        worker.schedule({
            synchronized(this) {
                if (generation != run || status == Status.CLOSED || session !== current) return@synchronized
                when (current.state) {
                    VoiceSession.State.READY -> if (status != Status.READY) publish(Status.READY)
                    // An unbound device is refused with an instruction, not a transport fault.
                    VoiceSession.State.FAILED, VoiceSession.State.CLOSED ->
                        publish(if (serverNotice != null) Status.ACTIVATION_REQUIRED else Status.ERROR)
                    else -> Unit
                }
                watch(run, current)
            }
        }, 100, TimeUnit.MILLISECONDS)
    }
    private fun publish(value: Status) { status = value; onState(value, activationCode) }
    @Synchronized override fun close() {
        if (status == Status.CLOSED) return
        generation++; session?.close(); session = null; activationCode = null
        publish(Status.CLOSED); worker.shutdownNow()
    }
}
