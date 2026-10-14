package com.mcpintelligence.fr3k.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import org.junit.Test

/** Explicitly opt the verification harness into disposable fixture capabilities. No product UI route. */
class CapabilityLabModeDeviceTest {
    private fun set(value:Boolean) {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Fr3kApplication
        check(app.getSharedPreferences("capability-lab",0).edit().putBoolean("fixtures",value).commit())
        app.capabilityHost.reset()
    }
    @Test fun enableFixtures()=set(true)
    @Test fun disableFixtures()=set(false)
}
