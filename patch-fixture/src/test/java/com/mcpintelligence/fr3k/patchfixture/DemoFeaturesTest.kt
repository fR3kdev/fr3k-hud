package com.mcpintelligence.fr3k.patchfixture

import org.junit.Assert.assertTrue
import org.junit.Test

class DemoFeaturesTest {
    @Test fun unmodifiedFixtureShowsOwnedBanner() {
        assertTrue(DemoFeatures.showBanner())
    }
}
