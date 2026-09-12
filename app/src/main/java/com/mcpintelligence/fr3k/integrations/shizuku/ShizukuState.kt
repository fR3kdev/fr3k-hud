package com.mcpintelligence.fr3k.integrations.shizuku

/**
 * Truth model for the Shizuku integration's runtime state.
 *
 * The previous design conflated three separate observations:
 *   1. is the Shizuku package installed?
 *   2. is the Shizuku OS service (shizuku_server) running?
 *   3. has our package been granted permission in SUI's admin list?
 *
 * and rendered "manager not installed" the moment ANY check returned
 * negative, even when the others were positive. The plan §7 observed
 * the live phone state (package installed + shizuku_server process
 * alive + binder callback pending) and demanded a six-state model that
 * never collapses to "manager not installed" while a binder is still
 * being awaited.
 *
 * Every state is reachable from [Unknown]. Transitions are driven by
 * [ShizukuEvent]s; the pure reducer is in [ShizukuStateReducer].
 */
sealed class ShizukuState {

    /**
     * Initial state — no observation has been made yet. The UI must not
     * render "manager not installed" while in this state.
     */
    object Unknown : ShizukuState()

    /**
     * Package is not present on the device. The UI may show
     * "install shizuku" CTAs.
     */
    object Missing : ShizukuState()

    /**
     * Package is installed but we have not yet observed the binder.
     * The plan explicitly forbids rendering "manager not installed"
     * here — the binder can take a few seconds to arrive after the
     * Shizuku app starts.
     */
    object ServerStarting : ShizukuState()

    /**
     * Binder is live but our package has not yet been granted
     * permission in SUI's admin list. The UI must surface the
     * grant permission CTA, not "manager not installed".
     */
    object BinderLivePermissionRequired : ShizukuState()

    /**
     * Binder is live and our package has permission. Operations
     * such as [ShizukuAdapter.shellCommand] may run.
     */
    object Ready : ShizukuState()

    /**
     * User explicitly denied permission in SUI. The UI must not
     * re-fire the grant dialog — they said no. A settings shortcut
     * is the only forward path.
     */
    object Denied : ShizukuState()

    /**
     * Binder died after being live (typically SUI service restart).
     * The UI should re-poll; the bridge will transition back to
     * [ServerStarting] on the next install check.
     */
    object Dead : ShizukuState()
}

/**
 * Discrete observations the [ShizukuBridge] raises against the reducer.
 *
 * Keeping the events as a sum type means the reducer is a pure function
 * from `(current, event) -> newState`. JVM tests pin its behaviour
 * without booting Shizuku.
 */
sealed class ShizukuEvent {
    /** Package install state observed via `PackageManager`. */
    data class InstallCheck(val present: Boolean) : ShizukuEvent()

    /**
     * OS-level process check via `ps -A | grep shizuku_server`.
     * Informational only — the binder is the source of truth.
     */
    data class OsProcessSeen(val running: Boolean) : ShizukuEvent()

    /** `Shizuku.OnBinderReceivedListener` fired. */
    object BinderReceived : ShizukuEvent()

    /** `Shizuku.OnBinderDeadListener` fired. */
    object BinderDied : ShizukuEvent()

    /**
     * `Shizuku.OnRequestPermissionResultListener` fired.
     * @param granted the user's response; -1 if grantResults was empty
     *                 (which the plan's reducer treats as denied).
     */
    data class PermissionResult(val granted: Boolean) : ShizukuEvent()

    /**
     * Fail-safe reconciliation raised by [ShizukuBridge] on binder
     * receipt/start. After a process restart the grant is already recorded
     * by Shizuku Manager, so a new `PermissionResult` callback never
     * arrives — the bridge re-checks the official
     * `Shizuku.checkSelfPermission()` plus the `API_V23` Android
     * permission fallback and reports the authoritative grant state here.
     *
     * @param granted true iff the permission is genuinely granted now.
     *                A `false` here is NOT a user denial — the reducer
     *                keeps the current requestable state instead of
     *                dropping into [ShizukuState.Denied].
     */
    data class PermissionReconciled(val granted: Boolean) : ShizukuEvent()
}

/**
 * Pure reducer — given a current state and an event, return the next
 * state. No side effects, no Android calls, no coroutines. JVM-testable.
 *
 * The rules pinned here are the ones the plan §7 enumerates:
 *  - InstallCheck(present=false) always lands in [ShizukuState.Missing].
 *  - InstallCheck(present=true) from [ShizukuState.Unknown] / [Dead] /
 *    [Missing] always lands in [ShizukuState.ServerStarting], never
 *    [ShizukuState.Missing].
 *  - [ShizukuEvent.BinderReceived] from [ShizukuState.ServerStarting]
 *    lands in [ShizukuState.BinderLivePermissionRequired].
 *  - [ShizukuEvent.PermissionResult] from
 *    [ShizukuState.BinderLivePermissionRequired] lands in [ShizukuState.Ready]
 *    if granted, [ShizukuState.Denied] otherwise.
 *  - [ShizukuEvent.BinderDied] from any "live" state lands in
 *    [ShizukuState.Dead].
 *  - Idempotency — duplicate BinderReceived / OsProcessSeen events
 *    do NOT regress a Ready state.
 */
object ShizukuStateReducer {

    fun reduce(current: ShizukuState, event: ShizukuEvent): ShizukuState =
        when (event) {
            is ShizukuEvent.InstallCheck ->
                if (event.present) {
                    // The plan rule: package present must NEVER render
                    // "manager not installed". Drop into ServerStarting
                    // and wait for the binder to arrive.
                    when (current) {
                        ShizukuState.Missing -> ShizukuState.ServerStarting
                        ShizukuState.Dead -> ShizukuState.ServerStarting
                        ShizukuState.Unknown -> ShizukuState.ServerStarting
                        // Stay put if we already know more.
                        ShizukuState.ServerStarting -> current
                        ShizukuState.BinderLivePermissionRequired -> current
                        ShizukuState.Ready -> current
                        ShizukuState.Denied -> current
                    }
                } else {
                    ShizukuState.Missing
                }

            is ShizukuEvent.OsProcessSeen -> when (current) {
                // Informational — only meaningful if we are still
                // waiting on the binder. Doesn't change Ready / Denied /
                // Dead (we already have a binder or have moved past it).
                ShizukuState.ServerStarting -> current
                ShizukuState.Unknown -> if (event.running) {
                    ShizukuState.ServerStarting
                } else {
                    current
                }
                else -> current
            }

            ShizukuEvent.BinderReceived -> when (current) {
                // Idempotency: receiving twice from a Ready state must
                // not regress to "permission required" — the grant is
                // already in SUI's admin list.
                ShizukuState.Ready -> current
                ShizukuState.Dead -> ShizukuState.BinderLivePermissionRequired
                ShizukuState.Denied -> current
                else -> ShizukuState.BinderLivePermissionRequired
            }

            ShizukuEvent.BinderDied -> when (current) {
                ShizukuState.Missing -> current
                ShizukuState.ServerStarting -> current
                else -> ShizukuState.Dead
            }

            is ShizukuEvent.PermissionResult ->
                if (event.granted) {
                    when (current) {
                        ShizukuState.BinderLivePermissionRequired -> ShizukuState.Ready
                        // Allow grant from any state — defensive.
                        else -> ShizukuState.Ready
                    }
                } else {
                    when (current) {
                        ShizukuState.Ready -> current
                        else -> ShizukuState.Denied
                    }
                }

            is ShizukuEvent.PermissionReconciled ->
                if (event.granted) {
                    when (current) {
                        // Normal fail-safe path: binder live + an
                        // authoritative check (official API or API_V23
                        // fallback) confirms the grant — reach Ready
                        // without a new permission-result callback.
                        ShizukuState.BinderLivePermissionRequired -> ShizukuState.Ready
                        // Idempotent: repeated reconciliation stays put.
                        ShizukuState.Ready -> current
                        // A now-granted permission supersedes a stale
                        // explicit denial (the user granted in SUI's
                        // settings path); the check is authoritative.
                        ShizukuState.Denied -> ShizukuState.Ready
                        // Never fabricate Ready without a live binder
                        // observation, or on a device without Shizuku —
                        // a checked grant alone must not resurrect
                        // Missing / Dead / Unknown.
                        else -> current
                    }
                } else {
                    // A reconciliation miss is NOT a denial: no dialog was
                    // refused. Keep the requestable state so the grant CTA
                    // stays up instead of collapsing to Denied.
                    current
                }
        }
}

/**
 * Pure permission-reconciliation policy. The Android-touching bridge
 * collapses the raw checks into booleans; this object decides what event
 * to raise, so the entire fail-safe decision is JVM-testable without
 * booting Shizuku or Android.
 *
 * Semantics (kept strict — never a weaker check):
 *  - [MANAGER_PERMISSION_API_V23] is the legacy Android permission Shizuku
 *    Manager grants to allowed packages (the live phone shows it granted).
 *  - The official `Shizuku.checkSelfPermission()` is the modern path.
 *  - Either authoritative channel proving the grant is enough — but a
 *    grant without a live binder is never [ShizukuState.Ready].
 */
object ShizukuPermissionReconciler {

    /** Legacy Android permission name Shizuku Manager grants to allowed apps. */
    const val MANAGER_PERMISSION_API_V23 = "moe.shizuku.manager.permission.API_V23"

    /** Model of android.content.pm.PackageManager.PERMISSION_GRANTED (0). */
    const val PERMISSION_GRANTED = 0

    /**
     * @param binderLive binder observed (`Shizuku.getBinder() != null`)
     * @param officialGranted official `Shizuku.checkSelfPermission()`
     *                        reported PERMISSION_GRANTED
     * @param legacyGranted PackageManager check of `API_V23` reported
     *                      PERMISSION_GRANTED
     */
    fun event(
        binderLive: Boolean,
        officialGranted: Boolean,
        legacyGranted: Boolean,
    ): ShizukuEvent {
        val granted = binderLive && (officialGranted || legacyGranted)
        return ShizukuEvent.PermissionReconciled(granted = granted)
    }
}