package com.mcpintelligence.fr3k.integrations.shizuku

import android.app.Application
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Application-scoped Shizuku integration.
 *
 * The plan §7 demands that the binder / permission state be tracked at
 * process scope, not per-Activity. The previous design had each Activity
 * building its own `ShizukuAdapter`, racing on `addBinderReceivedListener`,
 * and rendering "manager not installed" the moment any one check
 * returned negative — including the very common case where the binder
 * callback was still pending.
 *
 * Wiring contract:
 *   1. [Fr3kApplication.onCreate] calls [start] exactly once.
 *   2. [start] registers the three Shizuku listeners at process scope.
 *   3. [state] exposes the current [ShizukuState] as a [StateFlow] so
 *      every UI surface (Integrations panel, RADIAL menu, etc.) reads
 *      from one source of truth.
 *   4. [requestPermission] is the ONLY entry point for the grant flow.
 *      It refuses to fire unless the binder is live (state is
 *      [ShizukuState.ServerStarting] or
 *      [ShizukuState.BinderLivePermissionRequired]) — calling it before
 *      the binder arrives silently no-ops in SUI and leaves the user
 *      wondering why nothing happened.
 */
class ShizukuBridge private constructor() {

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.Unknown)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile private var application: Application? = null
    @Volatile private var listenerInstalled = false

    /**
     * Register all three listeners at process scope. Safe to call more
     * than once — subsequent calls are no-ops.
     */
    @Synchronized
    fun start(application: Application) {
        if (this.application == application && listenerInstalled) return
        this.application = application
        // Register all three listeners exactly once. The Shizuku AAR
        // accepts anonymous listeners and dedupes by reference for the
        // binder / dead listeners, but we still guard ourselves.
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        listenerInstalled = true
        Log.i(TAG, "ShizukuBridge started — listeners registered")
        // Kick off the first install check so the UI can react quickly
        // when the activity is created.
        observeInstallState()
        // Fail-safe start reconciliation: when the binder is already live
        // at process entry (restart while SUI is running), the binder
        // callback may never fire again — reconcile the pre-granted
        // permission now so the app reaches Ready without waiting for a
        // permission-result callback.
        if (runCatching { Shizuku.getBinder() != null }.getOrDefault(false)) {
            applyEvent(ShizukuEvent.BinderReceived)
            reconcilePermission()
        }
    }

    /**
     * Request the Shizuku runtime permission. Refuses to fire unless the
     * binder is live — calling Shizuku.requestPermission() before the
     * binder arrives silently no-ops on the SUI side and leaves the
     * user thinking the app is broken.
     *
     * Returns true if the request was dispatched, false if the bridge
     * refused (e.g. Shizuku not installed, no binder yet).
     */
    fun requestPermission(requestCode: Int): Boolean {
        val s = _state.value
        if (s !is ShizukuState.BinderLivePermissionRequired &&
            s !is ShizukuState.ServerStarting
        ) {
            Log.w(TAG, "requestPermission refused in state=$s")
            return false
        }
        return try {
            // Do NOT call Activity.requestPermissions() for Shizuku —
            // the plan §7 explicitly removed that path because the OS
            // dialog does not register our package in SUI's admin list;
            // only Shizuku.requestPermission(code) does.
            Shizuku.requestPermission(requestCode)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku.requestPermission failed: ${t.message}")
            false
        }
    }

    private fun observeInstallState() {
        val app = application ?: return
        val present = runCatching {
            app.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            true
        }.getOrDefault(false) ||
            runCatching {
                app.packageManager.getPackageInfo(SHIZUKU_PRIVILEGED_PKG, 0)
                true
            }.getOrDefault(false)
        applyEvent(ShizukuEvent.InstallCheck(present = present))
        // Also check whether the OS shizuku_server process is alive —
        // informational but useful for the ServerStarting → ready race.
        val osRunning = runCatching {
            Runtime.getRuntime().exec("ps -A").inputStream.bufferedReader().use {
                it.readText().contains("shizuku_server")
            }
        }.getOrDefault(false)
        applyEvent(ShizukuEvent.OsProcessSeen(running = osRunning))
    }

    /**
     * Fail-safe reconciliation of an already-granted Shizuku permission.
     *
     * The grant survives process restarts but the
     * `OnRequestPermissionResultListener` only fires when the user answers
     * a NEW grant dialog — after a restart it never fires again, so the
     * bridge would sit at [ShizukuState.BinderLivePermissionRequired]
     * forever despite SUI already listing our package. This re-checks the
     * authoritative grant through the official Shizuku API first, then the
     * legacy `moe.shizuku.manager.permission.API_V23` Android permission
     * (which Shizuku Manager grants to allowed packages — observed live on
     * the phone), and raises [ShizukuEvent.PermissionReconciled].
     *
     * The checks are deliberately strict: Ready is only ever reached when
     * the binder is live AND an authoritative channel reports the grant.
     * A failed reconciliation never collapses to Denied (that state means
     * the user refused a dialog — the grant CTA must stay visible).
     * The whole decision is delegated to [ShizukuPermissionReconciler] so
     * it is deterministic and JVM-testable.
     */
    private fun reconcilePermission() {
        val app = application ?: return
        val binderLive = runCatching { Shizuku.getBinder() != null }.getOrDefault(false)
        // Official path: requires the Shizuku service, throws when the
        // binder is gone — collapse to denied on any failure.
        val officialGranted = runCatching {
            Shizuku.checkSelfPermission() == ShizukuPermissionReconciler.PERMISSION_GRANTED
        }.getOrDefault(false)
        // Legacy Android path: the manager-granted signature-level
        // permission. minSdk 31 so Context.checkSelfPermission is safe.
        val legacyGranted = runCatching {
            app.checkSelfPermission(ShizukuPermissionReconciler.MANAGER_PERMISSION_API_V23) ==
                ShizukuPermissionReconciler.PERMISSION_GRANTED
        }.getOrDefault(false)
        Log.i(
            TAG,
            "reconcile binder=$binderLive official=$officialGranted legacyV23=$legacyGranted",
        )
        applyEvent(
            ShizukuPermissionReconciler.event(
                binderLive = binderLive,
                officialGranted = officialGranted,
                legacyGranted = legacyGranted,
            ),
        )
    }

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            Log.i(TAG, "binder received")
            applyEvent(ShizukuEvent.BinderReceived)
            // Fail-safe: reconcile an already-granted permission so a
            // process restart reaches Ready without a new
            // permission-result callback. The reducer never regresses a
            // Ready / Denied state from this.
            reconcilePermission()
        }

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            Log.w(TAG, "binder died")
            applyEvent(ShizukuEvent.BinderDied)
            // Re-install state observation so the next InstallCheck
            // event drives us back out of Dead if SUI is still running.
            observeInstallState()
        }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            Log.i(TAG, "permission result req=$requestCode grant=$grantResult")
            applyEvent(ShizukuEvent.PermissionResult(granted = grantResult == PackageManager_PERMISSION_GRANTED))
        }

    private fun applyEvent(event: ShizukuEvent) {
        _state.value = ShizukuStateReducer.reduce(_state.value, event)
    }

    companion object {
        private const val TAG = "FR3K.shizuku"
        private const val SHIZUKU_PKG = "moe.shizuku.api"
        private const val SHIZUKU_PRIVILEGED_PKG = "moe.shizuku.privileged.api"
        // android.content.pm.PackageManager.PERMISSION_GRANTED is 0
        // but we keep the value explicit to avoid an Android import in
        // a file that's otherwise platform-agnostic.
        private const val PackageManager_PERMISSION_GRANTED = 0

        @Volatile private var instance: ShizukuBridge? = null

        @Synchronized
        fun get(): ShizukuBridge {
            val existing = instance
            if (existing != null) return existing
            val created = ShizukuBridge()
            instance = created
            return created
        }

        /**
         * Convenience for [Fr3kApplication.onCreate]. Idempotent.
         */
        fun start(application: Application): ShizukuBridge {
            val b = get()
            b.start(application)
            return b
        }
    }
}