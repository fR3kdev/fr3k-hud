package com.mcpintelligence.fr3k.setup

import com.mcpintelligence.fr3k.integrations.termux.TermuxBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HermesTermuxSetupTest {
    @Test fun `verification first reconfigures and reads back safe selected profile`() = runBlocking {
        var configured = false
        val helper = HermesTermuxSetup(
            execute = { script, _ ->
                if (script.contains("FR3K_HERMES_CONFIGURED")) {
                    configured = true
                    TermuxBridge.Result("FR3K_HERMES_CONFIGURED", "", 0)
                } else {
                    val marker = Regex("FR3K_HERMES_READY_[a-zA-Z0-9]+").find(script)!!.value
                    TermuxBridge.Result(marker, "", 0)
                }
            },
            launchCommand = { true }, usable = { true }, arm64 = { true },
        )
        assertTrue(helper.verify("openrouter", "model", true).verified)
        assertTrue(configured)
    }
    @Test fun `install and wizard launch never enable Hermes`() = runBlocking {
        val helper = HermesTermuxSetup(
            execute = { _, _ -> TermuxBridge.Result("version", "", 0) },
            launchCommand = { true }, usable = { true }, arm64 = { true },
        )
        assertFalse(helper.install(true).verified)
        assertFalse(helper.openWizard(true).verified)
        assertFalse(helper.openAgent("openrouter", "model"))
    }

    @Test fun `only successful fresh live reply enables matching selection`() = runBlocking {
        var success = true
        var command = ""
        val helper = HermesTermuxSetup(
            execute = { script, _ ->
                command = script
                val marker = if (script.contains("FR3K_HERMES_CONFIGURED")) "FR3K_HERMES_CONFIGURED"
                    else Regex("FR3K_HERMES_READY_[a-zA-Z0-9]+").find(script)!!.value
                TermuxBridge.Result(marker, "", if (success) 0 else 124)
            },
            launchCommand = { true }, usable = { true }, arm64 = { true },
        )
        assertTrue(helper.verify("openrouter", "model", true).verified)
        assertTrue(command.contains("--oneshot"))
        assertTrue(helper.openAgent("openrouter", "model"))
        assertFalse(helper.openAgent("groq", "model"))
        success = false
        assertFalse(helper.verify("openrouter", "model", true).verified)
        assertFalse(helper.openAgent("openrouter", "model"))
    }

    @Test fun `setup never reflects provider error output that could contain a key`() = runBlocking {
        val helper = HermesTermuxSetup(
            execute = { _, _ -> TermuxBridge.Result("secret-canary", "secret-canary", 1) },
            launchCommand = { false }, usable = { true }, arm64 = { true },
        )
        assertFalse(helper.install(true).message.contains("secret-canary"))
        assertFalse(helper.configure("groq", "model", true).message.contains("secret-canary"))
        assertFalse(helper.verify("groq", "model", true).message.contains("secret-canary"))
        assertFalse(helper.openWizard(true).verified)
    }
}
