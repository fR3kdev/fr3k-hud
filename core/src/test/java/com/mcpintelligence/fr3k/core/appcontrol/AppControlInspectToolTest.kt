package com.mcpintelligence.fr3k.core.appcontrol

import com.mcpintelligence.fr3k.core.tools.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AppControlInspectToolTest {
    @Test fun reportsObservedIdentityAndNeverApplies() = runTest {
        val digest = "a".repeat(64)
        val tool = AppControlInspectTool { pkg -> TargetIdentity(pkg, 7, digest, listOf(digest)) }
        val result = tool.invoke(ToolContext("s", deviceId = "d"), ToolArgs(mapOf("package" to "com.example.target")))
            as ToolResult.Success
        assertEquals("7", result.structured["versionCode"])
        assertEquals("false", result.structured["applied"])
        assertEquals(digest, result.structured["signerSha256"])
    }
    @Test fun invalidPackageNeverReachesInspector() = runTest {
        var invoked = false
        val bus = AgentToolBus().also { b -> b.register(AppControlInspectTool { invoked = true; error("not expected") }) }
        val result = bus.dispatch(ToolContext("s", deviceId = "d"), "app.control.inspect",
            ToolArgs(mapOf("package" to "x;id")))
        assertTrue(result is ToolResult.Failure)
        assertFalse(invoked)
    }
}
