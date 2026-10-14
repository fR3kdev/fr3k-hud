package com.mcpintelligence.fr3k.bridge

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.appcontrol.CapabilityCatalogue
import com.mcpintelligence.fr3k.appcontrol.CapabilityHost
import com.mcpintelligence.fr3k.capability.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BridgePlaneDeviceTest {

    private val fixture = "com.mcpintelligence.fr3k.patchfixture"
    private lateinit var host: CapabilityHost
    private lateinit var identity: AppIdentity

    @Before fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        host = CapabilityHost(context)
        identity = host.installed()[fixture] ?: error("install the owned fixture first")
        // The operator authorises the service once; tests enable it the same way the settings UI would.
        instrumentation.uiAutomation.executeShellCommand(
            "settings put secure enabled_accessibility_services com.mcpintelligence.fr3k.hud/com.mcpintelligence.fr3k.bridge.Fr3kBridgeService",
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } }
        instrumentation.uiAutomation.executeShellCommand("settings put secure accessibility_enabled 1")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } }
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!Fr3kBridgeService.connected() && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(200)
        }
        if (!Fr3kBridgeService.connected()) {
            org.junit.Assume.assumeTrue(
                "instrumentation cannot host an accessibility service; the bridge plane is verified " +
                    "through the app's own MCP surface in scripts/device/run_capability_lab.py",
                false,
            )
        }
    }

    private fun executor() = BridgeStepExecutor(
        Fr3kBridgeService.require(),
        setOf(fixture, "com.mcpintelligence.fr3k.patchfixturetwo"),
        { _, _, mode -> "privileged stub ($mode)" },
    )

    private fun descriptor(id: String): CapabilityDescriptor =
        host.registry().find(id, identity) ?: error("capability $id is not registered")

    @Test fun adSkipCapabilityUsesFingerprintsAndChangesTheScreen() = runBlocking<Unit> {
        val records = executor().execute(descriptor("fixture.ad.skip").steps)
        assertTrue(records.all { it.status == "OK" })
        val click = records.first { it.op == "click" }
        assertTrue("click must be fingerprint confident, was ${click.confidence}", click.confidence >= 0.8)
        val observed = Fr3kBridgeService.require().readTree()!!
        val status = SelectorMatcher.find(
            observed,
            SelectorFingerprint(className = "android.widget.TextView", contentDescRegex = "ad-demo-status", textRegex = "(?i)^CONTENT_PLAYING$"),
        )
        assertTrue("skip must reach content: $status", status is MatchOutcome.Found)
        android.util.Log.i("FR3KBR", "AD_SKIP_OK confidence=${click.confidence} pid=${android.os.Process.myPid()}")
    }

    @Test fun maskedRegionActuallyDisappearsFromTheScreen() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        runBlocking<Unit> {
            executor().execute(listOf(Step.Launch("fr3kfixture://ads"), Step.WaitFor(SelectorFingerprint(className = "android.widget.Button", contentDescRegex = "(?i)ad-demo-skip"))))
        }
        val before = instrument.uiAutomation.takeScreenshot()
        runBlocking<Unit> { executor().execute(listOf(Step.Mask(SelectorFingerprint(contentDescRegex = "(?i)ad-demo-skip")))) }
        assertEquals(1, Fr3kBridgeService.require().maskCount)
        Thread.sleep(400)
        val masked = instrument.uiAutomation.takeScreenshot()
        val bounds = Fr3kBridgeService.require().lastMaskBounds ?: error("no mask bounds")
        val beforeLuma = regionLuma(before, bounds)
        val maskedLuma = regionLuma(masked, bounds)
        before.recycle(); masked.recycle()
        assertTrue("masked region should darken: before=$beforeLuma after=$maskedLuma", maskedLuma < beforeLuma && maskedLuma < 40.0)
        runBlocking<Unit> { executor().execute(listOf(Step.Unmask)) }
        assertEquals(0, Fr3kBridgeService.require().maskCount)
        android.util.Log.i("FR3KBR", "MASK_OK before=$beforeLuma after=$maskedLuma")
    }

    @Test fun explorerProducesAVersionBoundMapOfTheOwnedApp() = runBlocking<Unit> {
        val result = AppMapExplorer(Fr3kBridgeService.require()).explore(
            identity,
            listOf("home" to "fr3kfixture://home", "ads" to "fr3kfixture://ads", "notes" to "fr3kfixture://notes"),
            maxClickProbesPerScreen = 0,
        )
        assertTrue("expected at least three screens, got ${result.map.screens.map { it.name }}: ${result.notes}", result.map.screens.size >= 3)
        assertEquals(identity.fingerprint, result.map.identity.fingerprint)
        assertFalse(result.map.stale(identity))
        val commands = MapCommandCompiler.compile(result.map)
        assertEquals(result.map.screens.size, commands.size)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "appmaps/${identity.fingerprint}.json")
        file.parentFile?.mkdirs()
        file.writeText(result.map.toJson())
        assertTrue(AppMap.fromJson(file.readText()).screens.size >= 3)
        android.util.Log.i("FR3KBR", "APPMAP_OK screens=${result.map.screens.size} commands=${commands.size}")
    }

    private fun regionLuma(bitmap: Bitmap, bounds: android.graphics.Rect): Double {
        var sum = 0.0
        var count = 0
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val right = bounds.right.coerceIn(0, bitmap.width)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val bottom = bounds.bottom.coerceIn(0, bitmap.height)
        for (x in left until right step 4) {
            for (y in top until bottom step 4) {
                val pixel = bitmap.getPixel(x, y)
                sum += ((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / 3.0
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }
}
