package com.mcpintelligence.fr3k.providers

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-level UI safety contract; actual touch/layout behaviour requires device testing. */
class ProviderSetupPanelContractTest {
    @Test fun `panel masks transient keys observes persisted settings and reports saved changes`() {
        val file = File(System.getProperty("fr3k.appDir"), "src/main/java/com/mcpintelligence/fr3k/providers/ProviderSetupPanel.kt")
        assertTrue("Provider setup panel must exist", file.exists())
        val source = file.readText()
        listOf("PasswordVisualTransformation", "collectAsState", "service.validate(", "service.saveSelection(", "onSaved()", "onValidated()").forEach {
            assertTrue("Missing safety contract: $it", source.contains(it))
        }
        assertFalse("Do not persist raw keys in saved UI state", source.contains("rememberSaveable"))
    }
}
