package com.mcpintelligence.fr3k.integrations.blackwave

import org.junit.Assert.*
import org.junit.Test

class BlackwaveLocalParametersTest {
    @Test fun typedParametersAndQuotedBracesRemainData() {
        assertEquals(0, BlackwaveLocalParameters.parse("{\"volume\":0}").getInt("volume"))
        assertEquals(0, BlackwaveLocalParameters.parse(null).length())
        assertEquals("{[]}", BlackwaveLocalParameters.parse("{\"text\":\"{[]}\"}").getString("text"))
    }
    @Test fun unboundedOrMalformedParametersAreRefusedBeforeTheJsonParser() {
        for (value in listOf("[".repeat(1000), "{\"x\":".repeat(5), "x".repeat(4097), "{\"x\":\"unterminated}", "[]", "{}{}"))
            assertThrows(Exception::class.java) { BlackwaveLocalParameters.parse(value) }
    }
}
