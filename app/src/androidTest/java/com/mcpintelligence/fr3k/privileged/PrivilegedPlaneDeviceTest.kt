package com.mcpintelligence.fr3k.privileged

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.integrations.privileged.HudPrivilegedBridge
import com.mcpintelligence.fr3k.integrations.privileged.PrivilegedOpsClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the real HUD-owned privileged service. Requires the host to bootstrap it first. */
@RunWith(AndroidJUnit4::class)
class PrivilegedPlaneDeviceTest {

    private val fixture = "com.mcpintelligence.fr3k.patchfixture"
    private lateinit var client: PrivilegedOpsClient

    @Before fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        client = PrivilegedOpsClient(context)
        // The host starts the service while this test runs (shell tier, or root tier after `adb root`),
        // because the service needs a live HUD process and cannot be started from instrumentation.
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (HudPrivilegedBridge.serviceOrNull() == null && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(250)
        }
        assumeTrue(
            "privileged service not bootstrapped by the host",
            HudPrivilegedBridge.serviceOrNull() != null,
        )
    }

    @Test fun tierIsReportedFromTheRealServerUid() {
        val service = HudPrivilegedBridge.serviceOrNull()!!
        val tier = service.capabilityTier
        val uid = service.serverUid
        assertTrue("tier $tier must match uid $uid", (uid == 0 && tier == "ROOT") || (uid == 2000 && tier == "SHELL"))
        assertEquals(2, service.protocolVersion)
        android.util.Log.i("FR3KPV", "PRIVILEGE_TIER tier=$tier uid=$uid ops=${service.listAllowedOps().size}")
    }

    @Test fun appOpRoundTripIsVerifiedByReadBack() {
        val before = client.appOpMode(fixture, "WAKE_LOCK")
        val denied = client.setAppOp(fixture, "WAKE_LOCK", "deny")
        assertEquals("deny", denied.after)
        assertEquals("deny", client.appOpMode(fixture, "WAKE_LOCK"))
        val restored = client.setAppOp(fixture, "WAKE_LOCK", "allow")
        assertEquals("allow", restored.after)
        // Leave the device exactly as we found it, whatever that was.
        if (before == "default" || before == "unknown") client.setAppOp(fixture, "WAKE_LOCK", "default")
        assertEquals(if (before == "default" || before == "unknown") "default" else before, client.appOpMode(fixture, "WAKE_LOCK"))
        android.util.Log.i("FR3KPV", "APPOP_ROUND_TRIP before=$before denied=${denied.after} restored=${restored.after}")
    }

    @Test fun unlistedOpAndBadModeAreRefusedByTheServer() {
        val unlisted = runCatching { HudPrivilegedBridge.serviceOrNull()!!.getAppOp(fixture, "SHELL_EXEC") }
        assertTrue("unlisted op must be refused", unlisted.isFailure)
        val badMode = runCatching { HudPrivilegedBridge.serviceOrNull()!!.setAppOp(fixture, "WAKE_LOCK", "sudo") }
        assertTrue("bad mode must be refused", badMode.isFailure)
        val badPackage = runCatching { HudPrivilegedBridge.serviceOrNull()!!.getAppOp("com.x.y; rm -rf /", "WAKE_LOCK") }
        assertTrue("package must be validated", badPackage.isFailure)
    }

    @Test fun rootGatedOperationIsRefusedOnTheShellTierAndWorksOnTheRootTier() {
        val service = HudPrivilegedBridge.serviceOrNull()!!
        val tier = service.capabilityTier
        val attempt = runCatching { client.listPrivateDir(fixture) }
        if (tier == "SHELL") {
            assertTrue("shell tier must refuse a root-gated op", attempt.isFailure)
            assertTrue(
                "refusal must name the tier requirement",
                attempt.exceptionOrNull()?.message?.contains("ROOT") == true,
            )
            android.util.Log.i("FR3KPV", "ROOT_GATE enforced on SHELL: ${attempt.exceptionOrNull()?.message}")
        } else {
            val names = attempt.getOrThrow().toList()
            assertNotNull(names)
            android.util.Log.i("FR3KPV", "ROOT_UNLOCK granted on ROOT: entries=${names.size}")
        }
    }
}
