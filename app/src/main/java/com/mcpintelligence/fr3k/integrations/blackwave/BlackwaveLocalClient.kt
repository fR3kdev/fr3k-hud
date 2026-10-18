package com.mcpintelligence.fr3k.integrations.blackwave

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import com.fr3k.blackwave.ipc.IBlackwaveControl
import com.fr3k.blackwave.ipc.IBlackwaveEvents
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import com.mcpintelligence.fr3k.hud.BuildConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bounded same-phone adapter. Optional gateways continue using BlackwaveBridgeClient. */
class BlackwaveLocalClient(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("blackwave-local-association-v1", Context.MODE_PRIVATE)
    val packages get() = listOf("com.fr3k.blackwave") + if (BuildConfig.DEBUG) listOf("com.fr3k.blackwave.debug") else emptyList()

    @Suppress("DEPRECATION")
    fun signers(packageName: String): Set<String> {
        require(packageName in packages)
        val manager = context.packageManager
        val certificates = if (Build.VERSION.SDK_INT >= 28)
            manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners
        else manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures!!
        return certificates.map { certificate -> MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
            .joinToString("") { "%02x".format(it) } }.toSet()
    }

    fun associatedPackages(): List<String> = packages.filter { name ->
        try { signers(name).let { it.isNotEmpty() && it == preferences.getStringSet(name, emptySet()) } }
        catch (_: PackageManager.NameNotFoundException) { false }
    }

    fun approve(packageName: String, reviewed: Set<String>) {
        check(reviewed.isNotEmpty() && signers(packageName) == reviewed) { "BLACKWAVE changed during review" }
        check(preferences.edit().putStringSet(packageName, reviewed.toSet()).commit())
    }

    fun revoke(packageName: String) { check(preferences.edit().remove(packageName).commit()) }

    private fun reviewedSigners(packageName: String): Set<String> {
        val actual = signers(packageName)
        check(actual.isNotEmpty() && actual == preferences.getStringSet(packageName, emptySet())) {
            "Review the installed BLACKWAVE certificate first"
        }
        return actual
    }

    /** All Binder work runs off the UI thread; every exit unbinds the service. */
    private suspend fun <T> withRemote(packageName: String,
        block: suspend (IBlackwaveControl, JSONObject, Set<String>) -> T): T = withContext(Dispatchers.IO) {
        val reviewed = reviewedSigners(packageName)
        val released = AtomicBoolean(false)
        var connection: ServiceConnection? = null
        var bound = false
        try {
            val remote = withTimeout(5000) {
                suspendCancellableCoroutine<IBlackwaveControl> { continuation ->
                    val callback = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (continuation.isActive) continuation.resume(IBlackwaveControl.Stub.asInterface(binder))
                        }
                        override fun onServiceDisconnected(name: ComponentName) {
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("BLACKWAVE disconnected"))
                        }
                        override fun onNullBinding(name: ComponentName) {
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("BLACKWAVE refused binding"))
                        }
                        override fun onBindingDied(name: ComponentName) {
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("BLACKWAVE binding died"))
                        }
                    }
                    connection = callback
                    bound = context.bindService(Intent().setComponent(ComponentName(packageName,
                        "com.fr3k.blackwave.core.ipc.BlackwaveControlService")), callback, Context.BIND_AUTO_CREATE)
                    if (!bound && continuation.isActive) continuation.resumeWithException(IllegalStateException("BLACKWAVE service unavailable"))
                }
            }
            check(reviewedSigners(packageName) == reviewed) { "BLACKWAVE certificate changed" }
            val negotiation = JSONObject(remote.negotiate(1, 1))
            check(negotiation.getBoolean("compatible") && negotiation.getString("protocol") == "blackwave-local/1") { "Incompatible BLACKWAVE" }
            block(remote, negotiation, reviewed)
        } finally {
            if (bound && released.compareAndSet(false, true)) connection?.let { context.unbindService(it) }
        }
    }

    private fun boundedQuery(remote: IBlackwaveControl, request: JSONObject): JSONObject {
        val encoded = request.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= 8192)
        val result = remote.query(encoded)
        require(result.toByteArray(Charsets.UTF_8).size <= 49152)
        return JSONObject(result)
    }

    suspend fun query(packageName: String, request: JSONObject): JSONObject = withTimeout(5000) {
        withRemote(packageName) { remote, _, _ -> boundedQuery(remote, JSONObject(request.toString()).put("client_features", org.json.JSONArray(listOf("native-missions/1")))) }
    }

    /** One-shot wake-ups trigger durable pages. Consumer persists each page cursor after processing. */
    fun events(packageName: String, after: Long): Flow<JSONObject> = channelFlow {
        require(after >= 0)
        withRemote(packageName) { remote, negotiation, reviewed ->
            val features = negotiation.optJSONArray("features")
            if (features == null || (0 until features.length()).none { features.getString(it) == "event-wakeup/1" })
                throw UnsupportedOperationException("BLACKWAVE event callbacks unavailable")
            val wakes = Channel<Long>(Channel.CONFLATED)
            val listener = object : IBlackwaveEvents.Stub() {
                override fun onClosed(reason: String) {
                    if (context.packageManager.getPackagesForUid(Binder.getCallingUid())?.toSet() != setOf(packageName)) return
                    wakes.close(SecurityException("BLACKWAVE association changed; review before reconnecting"))
                }
                override fun onEventsAvailable(latestSequence: Long) {
                    if (context.packageManager.getPackagesForUid(Binder.getCallingUid())?.toSet() != setOf(packageName)) return
                    try {
                        check(reviewedSigners(packageName) == reviewed)
                        require(latestSequence >= 0)
                        wakes.trySend(latestSequence)
                    } catch (failure: Exception) { wakes.close(failure) }
                }
            }
            var cursor = after
            val death = IBinder.DeathRecipient { wakes.close(IllegalStateException("BLACKWAVE process ended; reconnect using the saved cursor")) }
            remote.asBinder().linkToDeath(death, 0)
            try {
                while (currentCoroutineContext().isActive) {
                    check(reviewedSigners(packageName) == reviewed) { "BLACKWAVE certificate changed" }
                    remote.subscribeEvents(cursor, listener)
                    do {
                        val page = boundedQuery(remote, JSONObject().put("version", 1).put("method", "events").put("after", cursor))
                        val events = page.getJSONArray("events")
                        val next = page.getLong("cursor")
                        var previous = cursor
                        for (i in 0 until events.length()) {
                            val sequence = events.getJSONObject(i).getLong("sequence")
                            check(sequence > previous) { "Non-monotonic event sequence" }
                            previous = sequence
                        }
                        check(next == previous && events.length() <= 50) { "Invalid event cursor/page" }
                        if (events.length() > 0) send(page)
                        cursor = next
                    } while (events.length() == 50)
                    // A queued old wake-up can cause one harmless extra drain; no cursor advances on notification alone.
                    wakes.receive()
                }
            } finally {
                try { remote.unsubscribeEvents(listener) } catch (_: Exception) { /* revoked/dead peer; service drops handle */ }
                remote.asBinder().unlinkToDeath(death, 0)
                wakes.close()
            }
        }
    }.buffer(0).flowOn(Dispatchers.IO)
}
