package com.mcpintelligence.fr3k.integrations.blackwave

import org.junit.Assert.assertTrue
import org.junit.Test

class BlackwaveDeviceHorizonTest {
    @Test
    fun horizonRemainsNonAuthoritative() {
        BlackwaveDeviceHorizon.validate()
        assertTrue(BlackwaveDeviceHorizon.entries.isNotEmpty())
    }
}
