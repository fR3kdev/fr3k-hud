package com.mcpintelligence.fr3k.integrations.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the [ShizukuState] model and the pure reducer that derives it
 * from observed events. The reducer is the only piece that can be JVM-
 * tested without booting Android or the Shizuku AAR — everything else
 * (the listener wiring, the state-flow exposure) is exercised on a
 * physical device.
 *
 * The plan §7 enumerates six states and rules out a recurring bug
 * where "package installed + OS server process alive + binder callback
 * pending" was being rendered as "manager not installed". This test
 * pins that the reducer never collapses to MISSING while a binder is
 * still being awaited.
 */
class ShizukuStateReducerTest {

    @Test fun missingWhenNothingInstalled() {
        val state = ShizukuStateReducer.reduce(
            current = ShizukuState.Unknown,
            event = ShizukuEvent.InstallCheck(present = false),
        )
        assertEquals(ShizukuState.Missing, state)
    }

    @Test fun serverStartingWhenPackageInstalledButBinderAbsent() {
        // The plan rule: package installed + OS server process alive +
        // binder callback pending must NOT render "manager not installed".
        val state = ShizukuStateReducer.reduce(
            current = ShizukuState.Unknown,
            event = ShizukuEvent.InstallCheck(present = true),
        )
        assertEquals(ShizukuState.ServerStarting, state)
    }

    @Test fun serverStartingAfterInstallCheckThenOsProcessSeen() {
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Unknown,
            event = ShizukuEvent.InstallCheck(present = true),
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.OsProcessSeen(running = true),
        )
        assertEquals(ShizukuState.ServerStarting, state)
    }

    @Test fun binderLivePermissionRequiredAfterBinderReceived() {
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Unknown,
            event = ShizukuEvent.InstallCheck(present = true),
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.BinderReceived,
        )
        assertEquals(ShizukuState.BinderLivePermissionRequired, state)
    }

    @Test fun binderLiveWhenPermissionAlreadyGranted() {
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.ServerStarting,
            event = ShizukuEvent.BinderReceived,
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.PermissionResult(granted = true),
        )
        assertEquals(ShizukuState.Ready, state)
    }

    @Test fun deniedAfterPermissionRejection() {
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.BinderLivePermissionRequired,
            event = ShizukuEvent.BinderReceived,
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.PermissionResult(granted = false),
        )
        assertEquals(ShizukuState.Denied, state)
    }

    @Test fun deadAfterBinderDies() {
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Ready,
            event = ShizukuEvent.PermissionResult(granted = true),
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.BinderDied,
        )
        assertEquals(ShizukuState.Dead, state)
    }

    @Test fun restartTransitionsDeadBackToServerStarting() {
        // After binder death, a new install check should not jump back
        // to Ready — the user still has to wait for the binder.
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Ready,
            event = ShizukuEvent.BinderDied,
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.InstallCheck(present = true),
        )
        assertEquals(ShizukuState.ServerStarting, state)
    }

    @Test fun binderReceivedIsIdempotent() {
        // The OnBinderReceivedListener can fire more than once (e.g. when
        // the Shizuku service restarts). Receiving twice must NOT change
        // a Ready state back to BinderLivePermissionRequired.
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Ready,
            event = ShizukuEvent.PermissionResult(granted = true),
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.BinderReceived,
        )
        assertEquals(ShizukuState.Ready, state)
    }

    // ---------- fail-safe reconciliation (0.4.18) ----------
    // The shield: HUD 0.4.17 got stuck at BinderLivePermissionRequired
    // after a process restart because the grant already recorded in SUI
    // only surfaces via a NEW permission-result callback, which never
    // fires again. The reducer must reach Ready from a reconciliation
    // event without weakening any permission check.

    @Test fun restartWithPreGrantedPermissionReconcilesToReady() {
        // Process restart: packages reinstall, SUI already lists us.
        // BinderReceived alone used to strand us at
        // BinderLivePermissionRequired — the reconciliation check is the
        // only way back to Ready without a new permission-result callback.
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Unknown,
            event = ShizukuEvent.InstallCheck(present = true),
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.BinderReceived,
        )
        assertEquals(ShizukuState.BinderLivePermissionRequired, state)
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.PermissionReconciled(granted = true),
        )
        assertEquals(ShizukuState.Ready, state)
    }

    @Test fun startWithAlreadyLiveBinderReconcilesToReady() {
        // The bridge start() path: process entry with the binder already
        // live. The bridge raises BinderReceived + PermissionReconciled
        // and must land in Ready (pre-granted permission case).
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Unknown,
            event = ShizukuEvent.InstallCheck(present = true),
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.BinderReceived,
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.PermissionReconciled(granted = true),
        )
        assertEquals(ShizukuState.Ready, state)
    }

    @Test fun reconciliationMissIsNotDenial() {
        // A reconcile that finds NO grant (e.g. binder arrived before the
        // user ever granted) must stay requestable — NOT collapse to
        // Denied, which would hide the grant CTA.
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Unknown,
            event = ShizukuEvent.InstallCheck(present = true),
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.BinderReceived,
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.PermissionReconciled(granted = false),
        )
        assertEquals(ShizukuState.BinderLivePermissionRequired, state)
    }

    @Test fun explicitDenialIsNotOverriddenByReconciliationFalse() {
        // User refused the grant dialog — Denied is terminal-ish for the
        // dialog path. A later asymmetric reconciliation (binder rebind
        // while still not granted) must NOT flip Denied back to
        // requestable, nor to Ready.
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.BinderLivePermissionRequired,
            event = ShizukuEvent.PermissionResult(granted = false),
        )
        assertEquals(ShizukuState.Denied, state)
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.BinderReceived,
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.PermissionReconciled(granted = false),
        )
        assertEquals(ShizukuState.Denied, state)
    }

    @Test fun nowGrantedPermissionResolvesStaleDenial() {
        // If the user later grants in SUI's settings path, an
        // authoritative reconciliation proves Ready — Denied is only
        // sticky against a still-grant-less reconcile.
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.BinderLivePermissionRequired,
            event = ShizukuEvent.PermissionResult(granted = false),
        )
        assertEquals(ShizukuState.Denied, state)
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.PermissionReconciled(granted = true),
        )
        assertEquals(ShizukuState.Ready, state)
    }

    @Test fun binderDeathThenRebindWithPregrantedPermissionReturnsToReady() {
        // Full lifecycle: Ready -> binder death -> Dead -> install check
        // -> ServerStarting -> binder rebind -> reconcile pre-grant ->
        // Ready. The permission survived the binder death (SUI grant is
        // process-independent).
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Ready,
            event = ShizukuEvent.PermissionResult(granted = true),
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.BinderDied,
        )
        assertEquals(ShizukuState.Dead, state)
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.InstallCheck(present = true),
        )
        assertEquals(ShizukuState.ServerStarting, state)
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.BinderReceived,
        )
        assertEquals(ShizukuState.BinderLivePermissionRequired, state)
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.PermissionReconciled(granted = true),
        )
        assertEquals(ShizukuState.Ready, state)
    }

    @Test fun binderRebindWithoutGrantStaysRequestable() {
        // Rebind after death with NO grant: must not fabricate Ready;
        // the user still has to grant.
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Dead,
            event = ShizukuEvent.BinderReceived,
        )
        assertEquals(ShizukuState.BinderLivePermissionRequired, state)
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.PermissionReconciled(granted = false),
        )
        assertEquals(ShizukuState.BinderLivePermissionRequired, state)
    }

    @Test fun reconciliationCannotFabricateReadyWithoutLiveBinder() {
        // The pure reconciler only reports granted=true when the binder
        // is live; the reducer is equally strict: a Reconciliation(true)
        // from Dead / Missing / Unknown must never fabricate Ready —
        // Ready means we can actually run IPC.
        assertEquals(
            ShizukuState.Dead,
            ShizukuStateReducer.reduce(
                current = ShizukuState.Dead,
                event = ShizukuEvent.PermissionReconciled(granted = true),
            ),
        )
        assertEquals(
            ShizukuState.Missing,
            ShizukuStateReducer.reduce(
                current = ShizukuState.Missing,
                event = ShizukuEvent.PermissionReconciled(granted = true),
            ),
        )
        assertEquals(
            ShizukuState.Unknown,
            ShizukuStateReducer.reduce(
                current = ShizukuState.Unknown,
                event = ShizukuEvent.PermissionReconciled(granted = true),
            ),
        )
    }

    @Test fun reconciliationFromReadyIsIdempotent() {
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Ready,
            event = ShizukuEvent.PermissionReconciled(granted = true),
        )
        assertEquals(ShizukuState.Ready, state)
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.PermissionReconciled(granted = false),
        )
        assertEquals(ShizukuState.Ready, state)
    }

    // ---------- fail-safe bridge wiring (source lint) ----------

    @Test fun bridgeReconcilesPermissionOnBinderReceived() {
        // Regression guard for the restart stall: the binder-received
        // path must feed a reconciliation event into the reducer.
        val source = readBridgeSource()
        assertTrue(
            "ShizukuBridge must reconcile the grant on binder receipt",
            source.contains("reconcilePermission()"),
        )
        assertTrue(
            "ShizukuBridge must raise PermissionReconciled after binder receipt",
            source.contains("ShizukuEvent.PermissionReconciled") ||
                source.contains("ShizukuPermissionReconciler.event"),
        )
    }

    @Test fun bridgeReconcilesAtStartWhenBinderAlreadyLive() {
        // Process restart may find the binder already live before the
        // listener fires; start() must reconcile too.
        val source = readBridgeSource()
        assertTrue(
            "ShizukuBridge start() must reconcile when the binder is already live",
            source.contains("Shizuku.getBinder()") && source.contains("reconcilePermission()"),
        )
    }

    @Test fun bridgeUsesOfficialCheckSelfPermissionAndApiV23Fallback() {
        // The two authoritative channels: official Shizuku API +
        // PackageManager API_V23 (live-phone observed). Neither may be
        // removed; removing the official call weakens the check.
        val source = readBridgeSource()
        assertTrue(
            "ShizukuBridge must use the official Shizuku.checkSelfPermission()",
            source.contains("Shizuku.checkSelfPermission()"),
        )
        assertTrue(
            "ShizukuBridge must fall back to moe.shizuku.manager.permission.API_V23",
            source.contains("moe.shizuku.manager.permission.API_V23"),
        )
        assertTrue(
            "ShizukuBridge must gate reconciliation on a live binder",
            source.contains("Shizuku.getBinder() != null"),
        )
    }

    // ---------- source lint ----------

    @Test fun adapterDoesNotCallActivityRequestPermissionsForShizuku() {
        // The plan §7: remove the Android-version branch that calls
        // Activity.requestPermissions() for Shizuku. Shizuku grants must
        // only go through Shizuku.requestPermission(code) after binder
        // received. A future regression that re-adds an OS permission
        // grant path will fail this lint.
        val source = readAdapterSource()
        val callsOsGrant = source.contains("activity.requestPermissions(") ||
            source.contains(".requestPermissions(arrayOf(\"moe.shizuku")
        assertFalse(
            "ShizukuAdapter must not call Activity.requestPermissions " +
                "for the Shizuku permission — grant via " +
                "Shizuku.requestPermission(code) only",
            callsOsGrant,
        )
    }

    @Test fun bridgeRegistersAllThreeListeners() {
        // The plan §7: application-scoped OnBinderReceivedListener +
        // OnBinderDeadListener + OnRequestPermissionResultListener. The
        // bridge file must register all three at start() time.
        val source = readBridgeSource()
        assertTrue(
            "ShizukuBridge must register OnBinderReceivedListener",
            source.contains("addBinderReceivedListener"),
        )
        assertTrue(
            "ShizukuBridge must register OnBinderDeadListener",
            source.contains("addBinderDeadListener"),
        )
        assertTrue(
            "ShizukuBridge must register OnRequestPermissionResultListener",
            source.contains("addRequestPermissionResultListener"),
        )
    }

    @Test fun applicationStartsShizukuBridge() {
        // The plan §7: ShizukuBridge is started from Fr3kApplication.
        // Without that, the listeners never register and the binder
        // callback never fires for the activity.
        val source = readApplicationSource()
        assertTrue(
            "Fr3kApplication must call ShizukuBridge.start() so the " +
                "listeners register at process entry",
            source.contains("ShizukuBridge.start") ||
                source.contains("ShizukuBridge.get") ||
                source.contains("shizukuBridge.start"),
        )
    }

    // ---------- helpers ----------

    private fun readAdapterSource(): String =
        readFile("app/src/main/java/com/mcpintelligence/fr3k/integrations/shizuku/ShizukuAdapter.kt")

    private fun readBridgeSource(): String =
        readFile("app/src/main/java/com/mcpintelligence/fr3k/integrations/shizuku/ShizukuBridge.kt")

    private fun readApplicationSource(): String =
        readFile("app/src/main/java/com/mcpintelligence/fr3k/Fr3kApplication.kt")

    private fun readFile(relativePath: String): String =
        com.mcpintelligence.fr3k.testing.RepoFiles.read(relativePath)
}