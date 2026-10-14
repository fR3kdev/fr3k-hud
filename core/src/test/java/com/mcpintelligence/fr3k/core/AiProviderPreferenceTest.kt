package com.mcpintelligence.fr3k.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AiProviderPreferenceTest {

    @Test fun anOpencodeKeyReplacesOpenrouterEvenWhenBothAreConfigured() {
        assertEquals(
            AiProviderPreference.OPENCODE,
            AiProviderPreference.resolve(explicit = null, openCodeKeyConfigured = true, openRouterKeyConfigured = true),
        )
    }

    @Test fun anOpencodeKeyAloneSelectsOpencode() {
        assertEquals(
            AiProviderPreference.OPENCODE,
            AiProviderPreference.resolve(null, openCodeKeyConfigured = true, openRouterKeyConfigured = false),
        )
    }

    @Test fun openrouterIsUsedOnlyWhenItIsTheOnlyConfiguredKey() {
        assertEquals(
            AiProviderPreference.OPENROUTER,
            AiProviderPreference.resolve(null, openCodeKeyConfigured = false, openRouterKeyConfigured = true),
        )
    }

    @Test fun withNoKeysTheZeroKeyOpencodePathIsTheWorkingDefault() {
        assertEquals(
            AiProviderPreference.OPENCODE,
            AiProviderPreference.resolve(null, openCodeKeyConfigured = false, openRouterKeyConfigured = false),
        )
    }

    @Test fun anUnreachableOpencodeWithNoKeyFallsBackToOpenrouter() {
        assertEquals(
            AiProviderPreference.OPENROUTER,
            AiProviderPreference.resolve(
                null,
                openCodeKeyConfigured = false,
                openRouterKeyConfigured = true,
                openCodeReachable = false,
            ),
        )
        // A configured key never silently downgrades to another provider.
        assertEquals(
            AiProviderPreference.OPENCODE,
            AiProviderPreference.resolve(
                null,
                openCodeKeyConfigured = true,
                openRouterKeyConfigured = true,
                openCodeReachable = false,
            ),
        )
    }

    @Test fun anExplicitChoiceAlwaysWins() {
        assertEquals(
            AiProviderPreference.OPENROUTER,
            AiProviderPreference.resolve("openrouter", openCodeKeyConfigured = true, openRouterKeyConfigured = true),
        )
        assertEquals(
            AiProviderPreference.OPENCODE,
            AiProviderPreference.resolve("OPENCODE", openCodeKeyConfigured = false, openRouterKeyConfigured = true),
        )
    }

    @Test fun junkOrAutoFallsThroughToTheKeyRules() {
        for (junk in listOf("", "  ", "auto", "hermes", "garbage")) {
            assertEquals(
                "junk '$junk' must not select a provider on its own",
                AiProviderPreference.OPENCODE,
                AiProviderPreference.resolve(junk, openCodeKeyConfigured = true, openRouterKeyConfigured = false),
            )
            assertEquals(
                "junk '$junk' must not select a provider on its own",
                AiProviderPreference.OPENROUTER,
                AiProviderPreference.resolve(junk, openCodeKeyConfigured = false, openRouterKeyConfigured = true),
            )
        }
    }
}
