package com.mcpintelligence.fr3k.appcontrol

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.integrations.privileged.HudPrivilegedBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddedPrivilegeDeviceTest {
    @Test fun bootstrappedServiceReportsActualUidAndPackageData() {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (HudPrivilegedBridge.serviceOrNull() == null && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
        }
        val service = HudPrivilegedBridge.serviceOrNull()
        assertNotNull("Bootstrap HUD-owned service via app_process first", service)
        service!!
        assertEquals(1, service.protocolVersion)
        assertTrue(service.serverUid == 2000 || service.serverUid == 0)
        val observed = service.inspectPackage("com.mcpintelligence.fr3k.patchfixture")
        assertEquals("com.mcpintelligence.fr3k.patchfixture", observed.getString("package"))
        assertEquals(1L, observed.getLong("versionCode"))
        assertTrue(observed.getString("basePath")!!.endsWith(".apk"))
        service.shutdown()
        val deathDeadline = android.os.SystemClock.elapsedRealtime() + 3000
        while (HudPrivilegedBridge.serviceOrNull() != null && android.os.SystemClock.elapsedRealtime() < deathDeadline) {
            Thread.sleep(20)
        }
        assertNull("Service death must invalidate cached capability", HudPrivilegedBridge.serviceOrNull())
    }
    @Test fun normalApplicationCannotPublishAnElevatedBinder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThrows(SecurityException::class.java) {
            context.contentResolver.call(
                android.net.Uri.parse("content://com.mcpintelligence.fr3k.hud.privileged"),
                "attach", null, Bundle())
        }
    }
}
