package com.mcpintelligence.fr3k.appcontrol

import org.junit.Assert.*
import org.junit.Test

class PreparationSheetTest {
    private val sha = "a".repeat(64)

    private fun sheet(warnings: List<String>, acknowledged: Boolean = false) = PreparationSheet(
        packageName = "com.example.target",
        appFingerprint = "f".repeat(64),
        engineId = "test-engine",
        engineVersion = "1",
        patchIds = listOf("module.apk"),
        patchBundleDigest = sha,
        sourceSha256 = sha,
        outputSha256 = sha,
        archivePath = "/data/archive/base.apk",
        archiveSha256 = sha,
        warnings = warnings,
        acknowledged = acknowledged,
    )

    @Test fun aWarningFreeBuildIsInstallableImmediately() {
        assertTrue(sheet(emptyList()).installable)
        sheet(emptyList()).requireInstallable()
    }

    @Test fun warningsBlockInstallUntilAcknowledged() {
        val drafted = sheet(listOf("re-signing changes the signing identity"))
        assertFalse("an unacknowledged warning must block installation", drafted.installable)
        assertThrows(IllegalArgumentException::class.java) { drafted.requireInstallable() }
        val approved = drafted.acknowledge()
        assertTrue(approved.acknowledged)
        assertTrue(approved.installable)
        approved.requireInstallable()
        // the original draft is untouched
        assertFalse(drafted.acknowledged)
    }

    @Test fun sheetRoundTripsWithItsWarnings() {
        val json = sheet(listOf("one warning"), acknowledged = true).toJson()
        assertEquals(1, json.getJSONArray("warnings").length())
        assertTrue(json.getBoolean("acknowledged"))
        assertEquals("com.example.target", json.getString("packageName"))
    }
}
