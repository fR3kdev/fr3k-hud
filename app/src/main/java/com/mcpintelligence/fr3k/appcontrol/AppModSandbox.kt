package com.mcpintelligence.fr3k.appcontrol

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object AppModSandbox {
    private val lock = Mutex()
    @OptIn(InternalCoroutinesApi::class)
    suspend fun run(context: Context, inputs: List<File>, outputs: List<File>, request: JSONObject) = lock.withLock {
        withTimeout(180_000) {
            var connection: ServiceConnection? = null
            var remoteWorker: IAppModSandbox? = null
            // The handler belongs to the lock holder. A queued caller never installs one.
            val owner = currentCoroutineContext().job
            var cancellation: DisposableHandle? = null
            val workerDeath = CompletableDeferred<Unit>()
            val deathRecipient = IBinder.DeathRecipient { workerDeath.complete(Unit) }
            try {
                val remote = suspendCancellableCoroutine<IAppModSandbox> { pending ->
                    val binding = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, service: IBinder) { if (pending.isActive) pending.resume(IAppModSandbox.Stub.asInterface(service)) }
                        override fun onServiceDisconnected(name: ComponentName) { if (pending.isActive) pending.resumeWithException(IllegalStateException("Sandbox died")) }
                        override fun onBindingDied(name: ComponentName) { if (pending.isActive) pending.resumeWithException(IllegalStateException("Sandbox binding died")) }
                        override fun onNullBinding(name: ComponentName) { if (pending.isActive) pending.resumeWithException(IllegalStateException("Sandbox refused binding")) }
                    }
                    connection = binding
                    check(context.bindService(Intent(context, AppModSandboxService::class.java), binding, Context.BIND_AUTO_CREATE))
                }
                remoteWorker = remote
                remote.asBinder().linkToDeath(deathRecipient, 0)
                cancellation = owner.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                    if (cause != null) runCatching { remote.cancel() }
                }
                runInterruptible(Dispatchers.IO) {
                    val inFd = ArrayList<ParcelFileDescriptor>()
                    val outFd = ArrayList<ParcelFileDescriptor>()
                    try {
                        inputs.forEach { inFd += ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
                        outputs.forEach { outFd += ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE) }
                        val result = JSONObject(remote.run(inFd.toTypedArray(), outFd.toTypedArray(), request.toString()))
                        check(result.getBoolean("ok")) { result.optString("error", "Sandbox failed") }
                    } finally { inFd.forEach { it.close() }; outFd.forEach { it.close() } }
                }
            } catch (failure: Exception) {
                // Killing the cancelled owner's Binder can report DeadObjectException before
                // runInterruptible observes cancellation. Preserve the owner's cancellation.
                currentCoroutineContext().ensureActive()
                throw failure
            } finally {
                cancellation?.dispose()
                withContext(NonCancellable) {
                    connection?.let { runCatching { context.unbindService(it) } }
                    remoteWorker?.let { worker ->
                        runCatching { worker.cancel() }
                        // Cancellation kills an isolated process asynchronously. Do not hand its
                        // still-cached binder to the next operation while it is dying.
                        if (worker.asBinder().isBinderAlive) withTimeout(10_000) { workerDeath.await() }
                        runCatching { worker.asBinder().unlinkToDeath(deathRecipient, 0) }
                    }
                }
            }
        }
    }
}
