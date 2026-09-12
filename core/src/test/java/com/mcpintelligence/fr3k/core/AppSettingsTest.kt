package com.mcpintelligence.fr3k.core

import org.junit.Assert.*
import org.junit.Test

class AppSettingsTest {
    @Test fun allSettingsSurviveReopeningTheStore() {
        var storage: String? = null
        val original = AppSettings.open({ storage }, { storage = it })
        val expected = AppSettings.Settings(
            hudEnabled = true, hudEdgeMarginDp = 24, hudPosition = 2,
            consentProfile = ConsentLevel.LOCAL_ONLY,
            hermesEndpoint = "https://local.example/agent",
            hermesAuthTokenKey = "test.hermes.key.reference",
            blackwaveEndpoint = "https://fleet.example:8878",
            blackwaveCredentialKey = "test.fleet.key.reference", blackwaveClientId = "test-client",
            termuxPackage = "test.termux", autoShareTargets = listOf("local-a", "local-b"),
            telemetryEnabled = false, experimentalFeatures = listOf("test-feature"),
        )
        original.update { expected }
        val reopened = AppSettings.open({ storage }, { storage = it })
        assertEquals(expected, reopened.settings.value)
        assertEquals(expected, original.settings.value)
    }

    @Test fun newInstallationUsesDefaultsWithoutWriting() {
        val settings = AppSettings.open({ null }, { error("must not write on read") })
        assertEquals(AppSettings.Settings(), settings.settings.value)
    }

    @Test fun olderSettingsUseDefaultsForMissingFields() {
        val settings = AppSettings.open({ "{\"hudEnabled\":true}" }, {})
        assertTrue(settings.settings.value.hudEnabled)
        assertEquals(AppSettings.Settings().blackwaveCredentialKey, settings.settings.value.blackwaveCredentialKey)
    }

    @Test fun corruptOrUnrecognizedConsentFailsClosed() {
        for (raw in listOf("not json", "{\"consentProfile\":\"future-profile\"}",
                           "{\"autoShareTargets\":42}")) {
            val settings = AppSettings.open({ raw }, {})
            assertEquals(ConsentLevel.LOCAL_ONLY, settings.settings.value.consentProfile)
        }
    }

    @Test fun failedWriteDoesNotPublishAnUnsavedChange() {
        val settings = AppSettings.open({ null }, { error("storage unavailable") })
        assertThrows(IllegalStateException::class.java) { settings.update { it.copy(hudEnabled = true) } }
        assertFalse(settings.settings.value.hudEnabled)
    }

    @Test fun sequentialUpdatesPreserveEarlierFields() {
        var storage: String? = null
        val settings = AppSettings.open({ storage }, { storage = it })
        settings.update { it.copy(hudEnabled = true) }
        settings.update { it.copy(consentProfile = ConsentLevel.PRIVATE) }
        val reopened = AppSettings.open({ storage }, {})
        assertTrue(reopened.settings.value.hudEnabled)
        assertEquals(ConsentLevel.PRIVATE, reopened.settings.value.consentProfile)
    }

    @Test fun newInstallationDefaultsArePhoneLoopback() {
        val settings = AppSettings.open({ null }, {})
        val defaults = AppSettings.Settings()
        // Standalone-phone: no desktop / mDNS name dependency.
        assertEquals("http://127.0.0.1:8082", settings.settings.value.hermesEndpoint)
        assertEquals("https://127.0.0.1:8878", settings.settings.value.blackwaveEndpoint)
        assertEquals(AppSettings.DEFAULT_HERMES_ENDPOINT, defaults.hermesEndpoint)
        assertEquals(AppSettings.DEFAULT_BLACKWAVE_ENDPOINT, defaults.blackwaveEndpoint)
        assertEquals("fr3k-hud", defaults.blackwaveClientId)
    }

    @Test fun legacyHermesDefaultMigratesToLoopback() {
        val settings = AppSettings.open(
            { "{\"hermesEndpoint\":\"https://hermes.local/api/v1/agent\"}" },
            {},
        )
        assertEquals(AppSettings.DEFAULT_HERMES_ENDPOINT, settings.settings.value.hermesEndpoint)
    }

    @Test fun legacyBlackwaveDefaultMigratesToLoopback() {
        val settings = AppSettings.open(
            { "{\"blackwaveEndpoint\":\"https://blackwave.local:8878\"}" },
            {},
        )
        assertEquals(AppSettings.DEFAULT_BLACKWAVE_ENDPOINT, settings.settings.value.blackwaveEndpoint)
    }

    @Test fun explicitCustomEndpointsArePreservedNotMigrated() {
        val settings = AppSettings.open(
            {
                "{\"hermesEndpoint\":\"https://fleet.example/agent\"," +
                    "\"blackwaveEndpoint\":\"https://fleet.example:8878\"}"
            },
            {},
        )
        assertEquals("https://fleet.example/agent", settings.settings.value.hermesEndpoint)
        assertEquals("https://fleet.example:8878", settings.settings.value.blackwaveEndpoint)
    }

    @Test fun loopbackDefaultsRoundTripThroughPersistence() {
        var storage: String? = null
        val original = AppSettings.open({ storage }, { storage = it })
        original.update { it.copy(hermesEndpoint = "http://127.0.0.1:8082", blackwaveEndpoint = "https://127.0.0.1:8878") }
        val reopened = AppSettings.open({ storage }, {})
        assertEquals("http://127.0.0.1:8082", reopened.settings.value.hermesEndpoint)
        assertEquals("https://127.0.0.1:8878", reopened.settings.value.blackwaveEndpoint)
    }
}
