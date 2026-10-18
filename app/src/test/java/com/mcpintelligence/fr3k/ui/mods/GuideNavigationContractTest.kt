package com.mcpintelligence.fr3k.ui.mods

import com.mcpintelligence.fr3k.appcontrol.ModGuideCatalogue
import com.mcpintelligence.fr3k.appcontrol.releaseProcedures
import org.junit.Assert.*
import org.junit.Test

class GuideNavigationContractTest {
    @Test fun eachProductionGuideGetsSpecificActionableDestination() {
        val routes = releaseProcedures().associate { it.id to guideDestination(it) }
        assertTrue("No routes", routes.isNotEmpty())
        assertEquals(GuideDestination.APP_IMPROVEMENTS, routes["mod.discovery"])
        assertEquals(GuideDestination.APP_IMPROVEMENTS, routes["mod.preparation"])
        assertEquals(GuideDestination.APP_IMPROVEMENTS, routes["mod.install.restore"])
        assertEquals(GuideDestination.PATCH_MODULE, routes["mod.modify.own.app"])
        assertEquals(GuideDestination.WIRELESS, routes["mod.shell"])
        assertEquals(GuideDestination.AI_SETTINGS, routes["mod.ai.go"])
        assertEquals(GuideDestination.MCP, routes["mod.agent"])
        assertEquals(GuideDestination.WORKFLOWS, routes["mod.workflows"])
        assertEquals(GuideDestination.APP_CONTROL, routes["mod.inspect.app"])
        assertEquals(routes.size, routes.values.count { it.buttonText.startsWith("GO TO ") })
        assertTrue(routes.values.none { it.buttonText.isBlank() })
    }

    @Test fun labAndCapabilityGuidesAlsoHaveReachableScreen() {
        val guides = ModGuideCatalogue.procedures() + ModGuideCatalogue.capabilities()
        assertTrue(guides.isNotEmpty())
        assertEquals(GuideDestination.MCP, guides.first { it.id == "mod.agent.access" }.let(::guideDestination))
        guides.forEach { guide ->
            val destination = guideDestination(guide)
            assertTrue("${guide.id}: no navigation label", destination.buttonText.startsWith("GO TO "))
        }
    }
}
