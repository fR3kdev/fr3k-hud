package com.mcpintelligence.fr3k.integrations.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuRevocationTest {
    @Test fun revokedPermissionDoesNotRemainReady() {
        assertEquals(ShizukuState.Denied, ShizukuStateReducer.reduce(
            ShizukuState.Ready, ShizukuEvent.PermissionResult(false)))
    }
    @Test fun staleGrantAfterBinderDeathDoesNotResurrectReady() {
        assertEquals(ShizukuState.Dead, ShizukuStateReducer.reduce(
            ShizukuState.Dead, ShizukuEvent.PermissionResult(true)))
    }
    @Test fun permissionWithoutObservedBinderDoesNotBecomeReady() {
        assertEquals(ShizukuState.ServerStarting, ShizukuStateReducer.reduce(
            ShizukuState.ServerStarting, ShizukuEvent.PermissionResult(true)))
    }
}
