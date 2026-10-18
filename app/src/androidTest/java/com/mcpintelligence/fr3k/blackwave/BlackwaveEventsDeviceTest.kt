package com.mcpintelligence.fr3k.blackwave

import android.content.*
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fr3k.blackwave.ipc.*
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveLocalClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.coroutines.resume

/** Requires the reversible BLACKWAVE two-unit mission fixture and actual HUD association. */
@RunWith(AndroidJUnit4::class)
class BlackwaveEventsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "com.fr3k.blackwave.debug"

    @Test fun newHudRetainsQueryCompatibilityWithOriginalService() = runBlocking {
        val preferences = context.getSharedPreferences("blackwave-local-association-v1", Context.MODE_PRIVATE)
        val prior = preferences.getStringSet(name, null)?.toSet()
        val client = BlackwaveLocalClient(context)
        try {
            client.approve(name, client.signers(name))
            assertEquals("METADATA_ONLY", client.query(name, JSONObject().put("version", 1).put("method", "health")).getString("status"))
            try { withTimeout(5000) { client.events(name, 0).first() }; fail("Original service advertised callbacks") }
            catch (_: UnsupportedOperationException) { }
        } finally {
            val editor = preferences.edit()
            if (prior == null) editor.remove(name) else editor.putStringSet(name, prior)
            check(editor.commit())
        }
    }
    private suspend fun <T> bound(block: suspend (IBlackwaveControl) -> T): T = withContext(Dispatchers.IO) {
        var connection: ServiceConnection? = null
        var connected = false
        try {
            val service = withTimeout(5000) { suspendCancellableCoroutine<IBlackwaveControl> { continuation ->
                connection = object : ServiceConnection {
                    override fun onServiceConnected(component: ComponentName, binder: IBinder) {
                        if (continuation.isActive) continuation.resume(IBlackwaveControl.Stub.asInterface(binder))
                    }
                    override fun onServiceDisconnected(component: ComponentName) = Unit
                }
                connected = context.bindService(Intent().setComponent(ComponentName(name,
                    "com.fr3k.blackwave.core.ipc.BlackwaveControlService")), connection!!, Context.BIND_AUTO_CREATE)
                check(connected)
            } }
            block(service)
        } finally { if (connected) context.unbindService(connection!!) }
    }
    private fun query(remote: IBlackwaveControl, method: String, body: JSONObject = JSONObject()) =
        JSONObject(remote.query(body.put("version", 1).put("method", method).toString()))
    private fun preview(remote: IBlackwaveControl, target: String): String = query(remote, "preview", JSONObject()
        .put("request_id", UUID.randomUUID().toString()).put("target_id", target).put("action", "evidence.enrollment")
        .put("parameters", JSONObject())).getJSONObject("job").getString("operation_id")
    private fun head(remote: IBlackwaveControl): Long {
        var cursor = 0L
        do {
            val page = query(remote, "events", JSONObject().put("after", cursor))
            cursor = page.getLong("cursor")
        } while (page.getJSONArray("events").length() == 50)
        return cursor
    }

    @Test fun actualUidCallbackIsOneShotAndReplaysDurableEvents() = runBlocking {
        bound { remote ->
            val features = JSONObject(remote.negotiate(1, 1)).getJSONArray("features")
            assertTrue((0 until features.length()).any { features.getString(it) == "event-wakeup/1" })
            val target = query(remote, "devices").getJSONArray("devices").getJSONObject(0).getString("identity_ref")
            val cursor = head(remote)
            val wakes = Channel<Long>(8)
            val peerUids = mutableListOf<Int>()
            val listener = object : IBlackwaveEvents.Stub() {
                override fun onEventsAvailable(latestSequence: Long) {
                    synchronized(peerUids) { peerUids.add(Binder.getCallingUid()) }
                    wakes.trySend(latestSequence)
                }
                override fun onClosed(reason: String) { wakes.close(SecurityException(reason)) }
            }
            try {
                remote.subscribeEvents(cursor, listener)
                val first = preview(remote, target)
                assertTrue(withTimeout(5000) { wakes.receive() } > cursor)
                val second = preview(remote, target)
                assertNull(withTimeoutOrNull(300) { wakes.receive() })
                val page = query(remote, "events", JSONObject().put("after", cursor))
                val values = page.getJSONArray("events")
                val ids = (0 until values.length()).map { values.getJSONObject(it).getString("operation_id") }
                assertTrue(first in ids && second in ids)
                remote.subscribeEvents(cursor, listener)
                assertTrue(withTimeout(5000) { wakes.receive() } >= page.getLong("cursor"))
                val expected = context.packageManager.getApplicationInfo(name, 0).uid
                assertTrue(synchronized(peerUids) { peerUids.all { it == expected } })
            } finally { remote.unsubscribeEvents(listener); wakes.close() }
        }
    }

    @Test fun flowReplaysThenReleasesListenerAndDetectsProcessDeath() = runBlocking {
        val preferences = context.getSharedPreferences("blackwave-local-association-v1", Context.MODE_PRIVATE)
        val prior = preferences.getStringSet(name, null)?.toSet()
        val client = BlackwaveLocalClient(context)
        try {
            client.approve(name, client.signers(name))
            bound { remote ->
                val target = query(remote, "devices").getJSONArray("devices").getJSONObject(0).getString("identity_ref")
                preview(remote, target)
            }
            val page = withTimeout(5000) { client.events(name, 0).first() }
            assertTrue(page.getJSONArray("events").length() > 0)
            // first() cancels/unregisters; another listener for the same actual UID must work.
            val ready = CompletableDeferred<Unit>()
            val stream = async {
                try {
                    client.events(name, 0).collect { ready.complete(Unit) }
                    "unexpected completion"
                } catch (failure: Exception) { failure.message.orEmpty() }
            }
            withTimeout(5000) { ready.await() }
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am force-stop $name").close()
            assertTrue(withTimeout(5000) { stream.await() }.contains("process ended"))
        } finally {
            val editor = preferences.edit()
            if (prior == null) editor.remove(name) else editor.putStringSet(name, prior)
            check(editor.commit())
        }
    }
}
