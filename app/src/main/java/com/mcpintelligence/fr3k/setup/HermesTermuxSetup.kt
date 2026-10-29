package com.mcpintelligence.fr3k.setup

import android.content.Context
import android.content.Intent
import android.os.Build
import com.mcpintelligence.fr3k.core.setup.HermesSetupPlan
import com.mcpintelligence.fr3k.integrations.termux.TermuxBridge
import com.mcpintelligence.fr3k.integrations.termux.TermuxCommandContract as Contract
import java.util.UUID

/** Optional Termux installation. No API accepts a key: RUN_COMMAND has no private stdin channel. */
class HermesTermuxSetup internal constructor(
    private val execute: suspend (String, Long) -> TermuxBridge.Result,
    private val launchCommand: (String) -> Boolean,
    private val usable: () -> Boolean,
    private val arm64: () -> Boolean,
) {
    constructor(context: Context, bridge: TermuxBridge) : this(
        execute = { command, timeout -> bridge.runRaw(command, timeout) },
        launchCommand = { command -> launchInTermux(context, command) },
        usable = { bridge.isUsable() },
        arm64 = { Build.SUPPORTED_ABIS.contains("arm64-v8a") },
    )
    data class Status(val message: String, val verified: Boolean = false)
    private var verifiedSelection: Pair<String, String>? = null

    fun available(): Boolean = usable()
    fun supported(): Boolean = arm64()

    suspend fun install(consented: Boolean): Status {
        verifiedSelection = null
        val command = HermesSetupPlan.install(consented, supported())
        val result = execute(command, 15 * 60_000L)
        return when (result.exitCode) {
            0 -> Status("Package installation returned success. Provider setup and live verification are still required.")
            124 -> Status("Installation timed out; it may still be running in Termux. Do not start another installation. Check Termux first.")
            else -> Status("Installation failed (exit ${result.exitCode}). Check Termux and the official package warning; no readiness claimed.")
        }
    }

    suspend fun configure(provider: String, model: String, consented: Boolean): Status {
        verifiedSelection = null
        val result = execute(HermesSetupPlan.configure(provider, model, consented), 60_000)
        return if (result.exitCode == 0 && result.stdout.trim() == "FR3K_HERMES_CONFIGURED")
            Status("Dedicated FR3K profile configured. Enter the provider key inside Termux's Hermes setup wizard; then verify here.")
        else Status("Configuration not confirmed (exit ${result.exitCode}). No keys were transferred. Check Termux.")
    }

    suspend fun verify(provider: String, model: String, consented: Boolean): Status {
        verifiedSelection = null
        val configured = execute(HermesSetupPlan.configure(provider, model, consented), 60_000)
        if (configured.exitCode != 0 || configured.stdout.trim() != "FR3K_HERMES_CONFIGURED" || configured.stderr.isNotBlank()) {
            return Status("Safe profile configuration could not be confirmed. No live test run; check Termux.")
        }
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val result = execute(HermesSetupPlan.verification(provider, model, nonce, consented), 120_000)
        val ok = HermesSetupPlan.isVerified(result.exitCode, result.stdout, result.stderr, nonce)
        if (ok) verifiedSelection = provider to model
        // Never reflect raw provider output: error text may contain credentials.
        return if (ok) Status("Live Hermes provider/model response verified for this session.", true)
        else Status("Live verification failed (exit ${result.exitCode}). Confirm the selected provider/model and key inside Termux. No Hermes action enabled.")
    }

    /** Launching a wizard is deliberately NOT proof of setup completion. */
    fun openWizard(consented: Boolean): Status {
        require(consented) { "Consent required" }
        verifiedSelection = null
        return if (launch(HermesSetupPlan.WIZARD)) Status("Wizard launch requested. Enter the key only into Hermes's masked prompt, never a shell command. Return here and verify; launch alone is not success.")
        else Status("Could not launch Termux. Grant Run commands permission and enable allow-external-apps in Termux.")
    }

    fun openAgent(provider: String, model: String): Boolean =
        verifiedSelection == provider to model && launch(HermesSetupPlan.OPEN)

    private fun launch(command: String): Boolean {
        if (!available()) return false
        return launchCommand(command)
    }

    companion object {
        private fun launchInTermux(context: Context, command: String): Boolean = runCatching {
            context.startService(Intent(Contract.ACTION).apply {
                setClassName(Contract.PACKAGE, Contract.SERVICE)
                putExtra(Contract.EXTRA_PATH, "/data/data/com.termux/files/usr/bin/sh")
                putExtra(Contract.EXTRA_ARGUMENTS, arrayOf("-c", command))
                putExtra(Contract.EXTRA_WORKDIR, "/data/data/com.termux/files/home")
                putExtra(Contract.EXTRA_BACKGROUND, false)
                putExtra(Contract.EXTRA_SESSION_ACTION, "0")
            }) != null
        }.getOrDefault(false)
    }
}
