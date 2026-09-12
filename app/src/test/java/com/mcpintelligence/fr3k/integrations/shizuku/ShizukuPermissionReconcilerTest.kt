package com.mcpintelligence.fr3k.integrations.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks [ShizukuPermissionReconciler] — the pure decision the bridge
 * uses to reconcile an already-granted Shizuku permission after a
 * process restart. No Android runtime is required; the bridge collapses
 * the raw checks into booleans and this object decides the event.
 *
 * The core invariant pinned here: **Ready is never fabricated**. A grant
 * reported by either authoritative channel (official Shizuku API or the
 * PackageManager-level `moe.shizuku.manager.permission.API_V23`) is
 * required AND the binder must be live — otherwise the reducer stays in
 * a requestable state and the user keeps the grant CTA.
 */
class ShizukuPermissionReconcilerTest {

    private fun eventOf(
        binderLive: Boolean,
        officialGranted: Boolean,
        legacyGranted: Boolean,
    ): ShizukuEvent.PermissionReconciled {
        val e = ShizukuPermissionReconciler.event(
            binderLive = binderLive,
            officialGranted = officialGranted,
            legacyGranted = legacyGranted,
        )
        assertTrue("reconciler must always emit PermissionReconciled", e is ShizukuEvent.PermissionReconciled)
        return e as ShizukuEvent.PermissionReconciled
    }

    @Test fun officialGrantWithLiveBinderReportsGranted() {
        val e = eventOf(binderLive = true, officialGranted = true, legacyGranted = false)
        assertTrue(e.granted)
    }

    @Test fun legacyApiV23GrantWithLiveBinderReportsGranted() {
        // Live-phone evidence: SUI granted moe.shizuku.manager.permission.API_V23
        // while the modern path was not yet observed. The fallback must
        // still prove the grant.
        val e = eventOf(binderLive = true, officialGranted = false, legacyGranted = true)
        assertTrue(e.granted)
    }

    @Test fun bothChannelsGrantedReportsGranted() {
        val e = eventOf(binderLive = true, officialGranted = true, legacyGranted = true)
        assertTrue(e.granted)
    }

    @Test fun noChannelGrantedReportsNotGranted() {
        val e = eventOf(binderLive = true, officialGranted = false, legacyGranted = false)
        assertEquals(false, e.granted)
    }

    @Test fun grantWithoutLiveBinderIsNeverGranted() {
        // The device may have the permission granted at PackageManager
        // level, but without a binder we cannot run IPC — Ready must not
        // be fabricated.
        val e1 = eventOf(binderLive = false, officialGranted = true, legacyGranted = true)
        assertEquals(false, e1.granted)
        val e2 = eventOf(binderLive = false, officialGranted = false, legacyGranted = true)
        assertEquals(false, e2.granted)
        val e3 = eventOf(binderLive = false, officialGranted = true, legacyGranted = false)
        assertEquals(false, e3.granted)
    }

    @Test fun permissionConstantMatchesAndroidPackageManager() {
        // android.content.pm.PackageManager.PERMISSION_GRANTED is 0 — the
        // bridge compares against this value; keep the semantic aligned
        // via the reconciler consumers so nobody rewrites the channel.
        assertEquals(0, ShizukuPermissionReconciler.PERMISSION_GRANTED)
        assertEquals("moe.shizuku.manager.permission.API_V23", ShizukuPermissionReconciler.MANAGER_PERMISSION_API_V23)
    }

    @Test fun reducerPairingBinderLivePlusGrantedReachesReady() {
        // Bridge-level end-to-end over the pure pieces: reconciler event
        // fed into the reducer lands Ready exactly when binder live +
        // grant proven; every other combination keeps the requestable
        // state.
        val binderLiveGranted = eventOf(binderLive = true, officialGranted = true, legacyGranted = false)
        val state = ShizukuStateReducer.reduce(
            current = ShizukuState.BinderLivePermissionRequired,
            event = binderLiveGranted,
        )
        assertEquals(ShizukuState.Ready, state)

        val noBinderGranted = eventOf(binderLive = false, officialGranted = true, legacyGranted = true)
        val stillRequestable = ShizukuStateReducer.reduce(
            current = ShizukuState.BinderLivePermissionRequired,
            event = noBinderGranted,
        )
        assertEquals(ShizukuState.BinderLivePermissionRequired, stillRequestable)
    }

    @Test fun reducerPairingMissStaysRequestableNotDenied() {
        val miss = eventOf(binderLive = true, officialGranted = false, legacyGranted = false)
        val state = ShizukuStateReducer.reduce(
            current = ShizukuState.BinderLivePermissionRequired,
            event = miss,
        )
        assertEquals(ShizukuState.BinderLivePermissionRequired, state)
    }
}