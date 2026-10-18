package com.mcpintelligence.fr3k.appcontrol.analysis

import com.mcpintelligence.fr3k.appcontrol.*
import org.junit.Assert.*
import org.junit.Test

class AppModCatalogueTest {
    @Test fun everyPublishedEntryReferencesAnOfflineGuide() {
        val guides = appModGuides().associateBy { it.id }
        assertEquals(6, AppModCatalogue.operators.size)
        assertEquals(3, AppModCatalogue.helpers.size)
        assertEquals(5, AppModCatalogue.tools.size)
        (AppModCatalogue.operators + AppModCatalogue.helpers + AppModCatalogue.tools + AppModCatalogue.controls).forEach { (entry, guide) ->
            assertTrue("$entry lacks guide $guide", guides.containsKey(guide))
        }
    }
}
