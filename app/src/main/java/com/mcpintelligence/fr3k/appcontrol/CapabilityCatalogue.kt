package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import com.mcpintelligence.fr3k.bridge.Fr3kBridgeService
import com.mcpintelligence.fr3k.capability.*
import org.json.JSONArray

/**
 * The capabilities this build can actually perform, each bound to the identity discovered on this
 * device at load time. Registration is code, not model output: the model can only pick from here.
 */
object CapabilityCatalogue {

    private const val FIXTURE = "com.mcpintelligence.fr3k.patchfixture"
    private const val FIXTURE_TWO = "com.mcpintelligence.fr3k.patchfixturetwo"
    private const val HUD = "com.mcpintelligence.fr3k.hud"

    val AD_SKIP_SUMMARY = "Skips ads that expose a skip control. Non-skippable in-stream ads are not affected."

    private fun banner() = SelectorFingerprint(viewId = "fixture-owned-banner")
    private fun title() = SelectorFingerprint(textRegex = "(?i)owned patch fixture")
    private fun skip() = SelectorFingerprint(className = "android.widget.Button", contentDescRegex = "(?i)ad-demo-skip")
    private fun content() = SelectorFingerprint(
        className = "android.widget.TextView",
        contentDescRegex = "ad-demo-status",
        textRegex = "(?i)^CONTENT_PLAYING$",
    )

    fun installed(context: Context): Map<String, AppIdentity> {
        val pm = context.packageManager
        val wanted = listOf(FIXTURE, FIXTURE_TWO, HUD)
        return wanted.mapNotNull { pkg ->
            runCatching { AndroidIdentityReader.of(context, pkg) }.getOrNull()?.let { pkg to it }
        }.toMap()
    }

    fun discover(context: Context): RegistryStore {
        val installed = installed(context)
        val store = RegistryStore()
        val verdict = SignerVerdict(true, "built-in catalogue")

        installed[FIXTURE]?.let { identity ->
            listOf(
                descriptor("fixture.demo.open", identity, RiskClass.OBSERVE, "Open the owned test fixture home screen",
                    listOf(Step.Launch("fr3kfixture://home"), Step.WaitFor(title()))),
                descriptor("fixture.banner.hide", identity, RiskClass.REVERSIBLE_WRITE, "Hide the owned test fixture banner",
                    listOf(Step.Launch("fr3kfixture://home"), Step.WaitFor(title()), Step.Broadcast(Fr3kBridgeService.ACTION_SET_OWNED_FIXTURE_BANNER, mapOf("visible" to "false")), Step.Observe(500), Step.AssertAbsent(banner()))),
                descriptor("fixture.banner.show", identity, RiskClass.REVERSIBLE_WRITE, "Show the owned test fixture banner",
                    listOf(Step.Launch("fr3kfixture://home"), Step.WaitFor(title()), Step.Broadcast(Fr3kBridgeService.ACTION_SET_OWNED_FIXTURE_BANNER, mapOf("visible" to "true")), Step.Observe(500), Step.AssertPresent(banner()))),
                descriptor("fixture.ad.skip", identity, RiskClass.REVERSIBLE_WRITE, AD_SKIP_SUMMARY,
                    listOf(Step.Launch("fr3kfixture://ads"), Step.WaitFor(skip()), Step.Click(skip()), Step.Observe(400), Step.AssertPresent(content()))),
                descriptor("fixture.ad.mask", identity, RiskClass.REVERSIBLE_WRITE, "Visually mask the simulated ad control (cosmetic only)",
                    listOf(Step.Launch("fr3kfixture://ads"), Step.WaitFor(skip()), Step.Mask(skip()), Step.Observe(300))),
                descriptor("fixture.ad.unmask", identity, RiskClass.REVERSIBLE_WRITE, "Remove the visual mask overlay",
                    listOf(Step.Unmask, Step.Observe(200))),
                descriptor("fixture.policy.deny_wake_lock", identity, RiskClass.PRIVILEGED, "Deny the owned test fixture the WAKE_LOCK app-op",
                    listOf(Step.PrivilegedOp(FIXTURE, "WAKE_LOCK", "deny"))),
                descriptor("fixture.policy.allow_wake_lock", identity, RiskClass.PRIVILEGED, "Restore the owned test fixture WAKE_LOCK app-op to allow",
                    listOf(Step.PrivilegedOp(FIXTURE, "WAKE_LOCK", "allow"))),
                descriptor("fixture.policy.read_wake_lock", identity, RiskClass.OBSERVE, "Read the owned test fixture WAKE_LOCK app-op",
                    listOf(Step.PrivilegedOp(FIXTURE, "WAKE_LOCK", "read"))),
            ).forEach { store.put(RegistryEntry(it, "fr3k.builtin.fixture", "builtin", verdict)) }
        }

        installed[FIXTURE_TWO]?.let { identity ->
            listOf(
                descriptor("target.two.open", identity, RiskClass.OBSERVE, "Open the owned second target",
                    listOf(Step.Launch("fr3ktwo://open"), Step.WaitFor(SelectorFingerprint(contentDescRegex = "(?i)target-two-title")))),
                descriptor("target.two.acknowledge", identity, RiskClass.REVERSIBLE_WRITE, "Press ACKNOWLEDGE on the owned second target",
                    listOf(Step.Launch("fr3ktwo://open"), Step.WaitFor(SelectorFingerprint(contentDescRegex = "target-two-ack")), Step.Click(SelectorFingerprint(contentDescRegex = "target-two-ack")), Step.Observe(400), Step.AssertPresent(SelectorFingerprint(className = "android.widget.TextView", textRegex = "(?i)acknowledged")))),
            ).forEach { store.put(RegistryEntry(it, "fr3k.builtin.targettwo", "builtin", verdict)) }
        }
        return store
    }

    private fun descriptor(
        id: String,
        identity: AppIdentity,
        risk: RiskClass,
        summary: String,
        steps: List<Step>,
    ) = CapabilityDescriptor(
        id = id,
        plane = if (risk == RiskClass.PRIVILEGED) Plane.PRIVILEGED else Plane.BRIDGE,
        risk = risk,
        summary = summary,
        identity = identity,
        steps = steps,
        parameters = emptyMap(),
        sourceBundle = "fr3k.builtin",
    )

    /** Fused cross-app workflow over the two owned targets. */
    fun demoWorkflow(installed: Map<String, AppIdentity>): WorkflowSpec? {
        val one = installed[FIXTURE] ?: return null
        val two = installed[FIXTURE_TWO] ?: return null
        return WorkflowSpec(
            id = "fr3k.demo.duo",
            name = "Owned duo: hide a banner then acknowledge a second app",
            version = 1,
            steps = listOf(
                WorkflowStep("fixture.banner.hide", one),
                WorkflowStep("target.two.acknowledge", two),
            ),
            triggers = listOf(Trigger.Manual, Trigger.Broadcast("com.mcpintelligence.fr3k.hud.RUN_WORKFLOW"), Trigger.McpTool("run_workflow")),
        )
    }

    fun registryJson(store: RegistryStore): String = JSONArray(store.ids()).toString()

    /**
     * Explicit trigger phrases per capability. The compiler matches these before falling back to
     * token overlap, which keeps natural-language mapping deterministic and reviewable.
     */
    fun phrases(): Map<String, List<String>> = mapOf(
        "fixture.demo.open" to listOf("open the owned fixture", "open the test fixture", "open fixture home"),
        "fixture.banner.hide" to listOf("hide the banner", "hide the fixture banner", "remove the banner", "turn off the banner"),
        "fixture.banner.show" to listOf("show the banner", "restore the banner", "bring back the banner", "turn on the banner"),
        "fixture.ad.skip" to listOf("skip the ads", "skip ads", "skip the ad", "remove ads from the owned fixture", "skip the simulated ad"),
        "fixture.ad.mask" to listOf("mask the ad", "cover the ad", "hide the ad control", "mask the simulated ad"),
        "fixture.ad.unmask" to listOf("unmask", "remove the mask", "clear the masks"),
        "fixture.policy.read_wake_lock" to listOf("read the wake lock op", "check wake lock", "what is the wake lock mode"),
        "fixture.policy.deny_wake_lock" to listOf("deny wake lock", "block wake lock", "stop the fixture using wake lock", "deny wake_lock"),
        "fixture.policy.allow_wake_lock" to listOf("allow wake lock", "restore wake lock", "allow wake_lock"),
        "target.two.open" to listOf("open the second target", "open target two"),
        "target.two.acknowledge" to listOf("acknowledge the second target", "press acknowledge", "acknowledge target two"),
    )
}
