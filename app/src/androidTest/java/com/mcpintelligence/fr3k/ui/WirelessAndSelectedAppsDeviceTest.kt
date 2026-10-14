package com.mcpintelligence.fr3k.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.appcontrol.*
import com.mcpintelligence.fr3k.capability.*
import com.mcpintelligence.fr3k.integrations.privileged.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WirelessAndSelectedAppsDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app get()=inst.targetContext.applicationContext as Fr3kApplication
    @Test fun realOnDevicePairingStartsVerifiedShellAndReconnects() {
        val args=InstrumentationRegistry.getArguments()
        val code=requireNotNull(args.getString("pairingCode")) { "Host must open Android's live pairing-code sheet" }
        val pairPort=args.getString("pairingPort")!!.toInt()
        val port=args.getString("connectionPort")!!.toInt()
        WirelessDebugging(app).use { check(it.pair("127.0.0.1",pairPort,code)) }
        WirelessDebugging(app).use { assertTrue(it.startHud(app,"127.0.0.1",port).startsWith("CONNECTED")) }
        val service=HudPrivilegedBridge.serviceOrNull()!!
        assertEquals(2000,service.serverUid)
        assertEquals("SHELL",service.capabilityTier)
        assertEquals("com.termux",service.inspectPackage("com.termux").getString("package"))
        service.shutdown()
        Thread.sleep(500)
        WirelessDebugging(app).use { assertTrue(it.startHud(app,"127.0.0.1",port).startsWith("CONNECTED")) }
        File(app.filesDir,"ui-audit/wireless.txt").apply { parentFile!!.mkdirs() }.writeText("Real Android pairing PASS; encrypted identity reload PASS; Binder UID 2000 SHELL PASS; disconnect/reconnect PASS. No PIN or private key stored in evidence.")
    }
    @Test fun selectedNonFixtureAppGetsExecutableControlsAndCompleteGuides() = runBlocking {
        val target="com.termux"
        val existed=target in CapabilityCatalogue.selectedPackages(app)
        try {
            CapabilityCatalogue.select(app,target,true)
            val registry=app.capabilityHost.registry(true)
            val actions=registry.all().map { it.descriptor }.filter { it.id.startsWith("app.$target.") }
            assertEquals(41,actions.size)
            val guides=ModGuideCatalogue.all(app)
            actions.forEach { action ->
                val guide=guides.single { action.id in it.capabilityIds }
                assertTrue(guide.steps.size>=3);assertTrue(guide.limits.isNotBlank());assertTrue(guide.reversible.isNotBlank())
                assertEquals(AndroidIdentityReader.of(app,target),action.identity)
            }
            val read=actions.single { it.id.endsWith("wake_lock.read") }
            val report=app.capabilityHost.workflowRunner().run(WorkflowSpec("selected-read","Read selected app op",1,listOf(WorkflowStep(read.id,read.identity)),listOf(Trigger.Manual)))
            assertTrue(report.note,report.ok)
            assertTrue(report.records.single().detail.contains("op=WAKE_LOCK mode="))
            val client=PrivilegedOpsClient(app)
            val original=client.appOpMode(target,"WAKE_LOCK")
            try {
                val deny=actions.single { it.id.endsWith("wake_lock.deny") }
                val changed=app.capabilityHost.workflowRunner().run(WorkflowSpec("selected-write","Deny selected app op",1,listOf(WorkflowStep(deny.id,deny.identity)),listOf(Trigger.Manual)))
                assertTrue(changed.note,changed.ok);assertEquals("deny",client.appOpMode(target,"WAKE_LOCK"))
                assertEquals(InverseOp.RestorePrivilegedOp(target,"WAKE_LOCK",original),app.capabilityHost.workflowRunner().inverseFor(deny,changed.records))
            } finally { client.setAppOp(target,"WAKE_LOCK",original) }
            File(app.filesDir,"ui-audit/selected-app.txt").writeText("com.termux: 41 actions, 41 complete guides, real shell read/write, original-mode inverse and restore PASS")
        } finally { CapabilityCatalogue.select(app,target,existed);app.capabilityHost.reset() }
    }
}
