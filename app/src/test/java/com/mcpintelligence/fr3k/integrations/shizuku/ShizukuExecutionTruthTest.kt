package com.mcpintelligence.fr3k.integrations.shizuku

import android.os.IBinder
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ShizukuExecutionTruthTest {
    @Test fun unqualifiedProtocolNeverTouchesBinderOrReportsSuccess() {
        val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java)) { _, _, _ ->
            throw AssertionError("Unqualified protocol touched binder")
        } as IBinder
        val op = ShizukuCommandExecutor.Operation.from("GetPackageInfo", "com.termux")
            as ShizukuCommandExecutor.Operation.Allowed
        val result = ShizukuCommandExecutor.execute(binder, op)
        assertNotEquals(0, result.exitCode)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.contains("unsupported", ignoreCase = true))
    }
    @Test fun packageInspectionRejectsShellSyntax() {
        assertTrue(ShizukuCommandExecutor.Operation.from("GetPackageInfo", "x;id")
            is ShizukuCommandExecutor.Operation.Denied)
    }
    @Test fun settingsInspectionRejectsCommandInjection() {
        assertTrue(ShizukuCommandExecutor.Operation.from("ReadSystemSetting",
            "settings:global:airplane_mode_on;id") is ShizukuCommandExecutor.Operation.Denied)
    }
    @Test fun installPathAloneIsNotTargetAuthorisation() {
        assertTrue(ShizukuCommandExecutor.Operation.from("InstallApprovedApk",
            "/data/user/0/com.mcpintelligence.fr3k.hud/files/approved.apk")
            is ShizukuCommandExecutor.Operation.Denied)
    }
}
