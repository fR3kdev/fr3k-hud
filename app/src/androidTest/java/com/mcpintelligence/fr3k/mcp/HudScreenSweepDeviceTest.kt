package com.mcpintelligence.fr3k.mcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.appcontrol.CapabilityHost
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/**
 * Real emulator Activity/Accessibility smoke sweep (NOT hardware or full button acceptance).
 * Run explicitly only on a dedicated test device; it changes the foreground activity.
 */
@RunWith(AndroidJUnit4::class)
class HudScreenSweepDeviceTest {
    @Test fun enumerateAndOpenEveryRegisteredHudSurface() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        val server = Fr3kMcpServer(CapabilityHost(context), port = 18794)
        server.start()
        try {
            server.diagnostics.allowControlsTenMinutes()
            val array = server.diagnostics.screens().getJSONArray("screens")
            var registered = 0
            var launched = 0
            var observed = 0
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.getString("id")
                if (!item.getBoolean("manifestRegistered")) {
                    android.util.Log.w("FR3KSWEEP", "SCREEN " + id + " MANIFEST_MISSING")
                    continue
                }
                registered++
                if (!item.getBoolean("directLaunchSupported")) {
                    android.util.Log.i("FR3KSWEEP", "SCREEN " + id + " BLOCKED_NEEDS_TRIGGER")
                    continue
                }
                val result = server.diagnostics.openScreen(id)
                if (!result.optBoolean("ok")) {
                    android.util.Log.w("FR3KSWEEP", "SCREEN " + id + " LAUNCH_REJECTED")
                    continue
                }
                launched++
                Thread.sleep(950)
                val root = ins.uiAutomation.rootInActiveWindow
                val correctPackage = root?.packageName?.toString() == context.packageName
                val count = root?.childCount ?: -1
                if (correctPackage && count >= 0) observed++
                android.util.Log.i("FR3KSWEEP", "SCREEN " + id + " " +
                    (if (correctPackage && count >= 0) "OBSERVED" else "UNKNOWN") + " rootChildren=" + count)
            }
            android.util.Log.i("FR3KSWEEP", "SUMMARY registered=" + registered +
                " launched=" + launched + " uiObserved=" + observed + " total=" + array.length())
            assertTrue(registered >= 18)
            assertEquals("Direct-launch screens must accept launch requests", registered - 2, launched)
            // An OBSERVED root only proves the process exposed some view, not every control.
            assertTrue("Most screens should expose HUD-root visibility", observed >= registered / 2)
        } finally {
            server.stop()
        }
    }
}
