package com.mcpintelligence.fr3k.appcontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class AppOpModeParsingTest {
    private fun parse(output:String):String {
        val method=Class.forName("com.mcpintelligence.fr3k.privileged.HudPrivilegedMain")
            .getDeclaredMethod("parseAppOpMode",String::class.java,String::class.java)
        method.isAccessible=true
        return method.invoke(null,output,"WAKE_LOCK") as String
    }
    @Test fun absentOverrideRestoresDefaultRatherThanAssumingEffectiveAllow() {
        assertEquals("default",parse("No operations.\nDefault mode: allow\n"))
    }
    @Test fun modeCanHaveUsageMetadataAfterSemicolon() {
        assertEquals("deny",parse("WAKE_LOCK: deny; time=+3s ago\n"))
        assertEquals("allow",parse("WAKE_LOCK: allow\n"))
    }
    @Test fun unrecognisedOutputDoesNotInventAnInverse() {
        assertEquals("unknown",parse("Error: package could not be read\n"))
    }
}
