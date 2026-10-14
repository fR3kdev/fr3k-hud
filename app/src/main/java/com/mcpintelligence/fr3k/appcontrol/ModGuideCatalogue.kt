package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import com.mcpintelligence.fr3k.capability.ModCategory
import com.mcpintelligence.fr3k.capability.ModEnvironment
import com.mcpintelligence.fr3k.capability.ModGuide
import com.mcpintelligence.fr3k.capability.ModRequirement
import com.mcpintelligence.fr3k.integrations.privileged.HudPrivilegedBridge

/**
 * Every guide this build ships. Two families:
 *
 *  - [procedures] are the general walkthroughs: inspect an app, change one you own, automate a chore,
 *    fuse apps together, use the privileged planes, drive it all from your own agent.
 *  - [capabilities] is one card per registered capability, so nothing executable is reachable only by
 *    guessing a phrase.
 *
 * [coverage] is the invariant that keeps this true, and `ModGuideCoverageTest` fails the build if a
 * capability is ever added without a guide explaining it.
 */
object ModGuideCatalogue {

    private const val FIXTURE = "com.mcpintelligence.fr3k.patchfixture"
    private const val FIXTURE_TWO = "com.mcpintelligence.fr3k.patchfixturetwo"

    /** Live state of the device, read once when the screen opens. */
    fun environment(context: Context): ModEnvironment {
        val bridge = runCatching {
            com.mcpintelligence.fr3k.bridge.Fr3kBridgeService.instanceOrNull() != null
        }.getOrDefault(false)
        val tier = runCatching { HudPrivilegedBridge.serviceOrNull()?.capabilityTier }.getOrNull()
        return ModEnvironment(
            bridgeBound = bridge,
            privilegedShell = tier == "SHELL" || tier == "ROOT",
            privilegedRoot = tier == "ROOT",
        )
    }

    fun procedures(): List<ModGuide> = listOf(
        ModGuide(
            id = "mod.inspect.app",
            title = "Look at an app before you touch it",
            category = ModCategory.INSPECT,
            oneLiner = "Version, signer and exact APK hashes for any installed app.",
            whatItDoes = "Reads the installed package's version, signing certificate SHA-256 and the SHA-256 of " +
                "every APK and split. Read-only: nothing is modified, no app data is read, and the app does not " +
                "have to be running.",
            requirements = emptyList(),
            steps = listOf(
                "Open APP CONTROL from the Tools tab on the Command Deck, or open APP MODS → INSPECT.",
                "Type the package name of the app you want to look at (for example com.example.app).",
                "Press INSPECT SELECTED APP — READ ONLY.",
                "Read the version, signer and per-APK hashes it reports.",
                "Keep that output: if you later prepare a patched build, those hashes are what proves the app " +
                    "has not changed underneath you.",
            ),
            examplePhrase = "inspect com.example.app",
            capabilityIds = emptyList(),
            runsOn = "any installed app on this device",
            limits = "It reports identity only. It cannot tell you whether a recipe exists for that build — " +
                "for third-party apps it does not.",
            reversible = "Nothing to undo; no state is written.",
        ),
        ModGuide(
            id = "mod.modify.own.app",
            title = "Change how an app you own behaves",
            category = ModCategory.MODIFY,
            oneLiner = "Prepare, sign and install a modified build of your own app, with an undo archive.",
            whatItDoes = "Runs the embedded patch engine against an APK you own, signs the result with a key " +
                "generated inside this installation's AndroidKeyStore (never a bundled key), re-verifies it with " +
                "apksigner, and hands it to Android's installer for you to approve. The original is archived " +
                "first so the change can be reversed.",
            requirements = listOf(ModRequirement.OWNED_TARGET),
            steps = listOf(
                "Have the APK you want to change installed, and know its package name.",
                "Open APP MODS → MODIFY → this guide, then APP CONTROL.",
                "Press LAB: PREPARE OWNED FIXTURE ON THIS DEVICE and wait for the SHA-256 to appear.",
                "Press LAB: REVIEW OWNED FIXTURE INSTALLATION. Read the warnings; they are not decoration.",
                "Acknowledge the warnings, then approve Android's installer prompt.",
                "Re-run the app and check the behaviour you changed.",
                "To undo: the original APK is in the archive; reinstall it from APP CONTROL.",
            ),
            examplePhrase = "prepare my app and show me the review sheet",
            capabilityIds = emptyList(),
            runsOn = "an app you built or installed yourself — currently qualified end-to-end on the owned test fixture",
            limits = "A patched build is a re-signed build. Any app that pins its original signer loses sign-in, " +
                "and installing it may require removing the old copy first, which deletes that app's data. " +
                "HUD never uninstalls it for you, and never forges another developer's signature.",
            reversible = "Reinstall the archived original, or uninstall the patched build.",
        ),
        ModGuide(
            id = "mod.autorun.taps",
            title = "Make a repeated tap sequence run itself",
            category = ModCategory.AUTOMATE,
            oneLiner = "Record what an app looks like, then call its screens as commands.",
            whatItDoes = "Explores an app's declared entry points, records each screen it lands on, and compiles " +
                "those screens into named commands. A command is a fixed sequence of steps — launch, wait for a " +
                "fingerprint, tap, type, assert — so it either does the thing or reports exactly where it stopped.",
            requirements = listOf(ModRequirement.BRIDGE),
            steps = listOf(
                "Switch on Settings → Accessibility → fR3k HUD.",
                "Open APP MODS → AUTOMATE → this guide.",
                "Record the app map: HUD walks the app's deeplink entries and records the screens it reaches.",
                "Review the generated commands — each one is bound to that app's exact version and signer.",
                "Open Command Deck → Workflows to select a registered capability, or call MCP render_plan with its phrase and review the result.",
                "Re-record the app map after that app updates; HUD refuses a command when the build has drifted.",
            ),
            examplePhrase = "record the app map for the owned fixture",
            capabilityIds = emptyList(),
            runsOn = "apps whose screens HUD can see; verified with the owned test fixture",
            limits = "A recorded command is only as reliable as its fingerprints. HUD refuses ambiguous " +
                "matches, and refuses a stale build rather than tapping something that merely looks right. " +
                "System surfaces (SystemUI, Settings, the installer) are never automated.",
            reversible = "Delete the recorded map; it is local data and nothing else changes.",
        ),
        ModGuide(
            id = "mod.combine.apps",
            title = "Fuse several apps into one flow",
            category = ModCategory.COMBINE,
            oneLiner = "One press that drives two or more apps in order.",
            whatItDoes = "Chains registered capabilities — each one bound to its own app's exact identity — into a " +
                "single workflow. HUD checks every step's target before running anything, so a flow across two " +
                "apps cannot half-execute against a stale build.",
            requirements = listOf(ModRequirement.BRIDGE),
            steps = listOf(
                "Have a capability recorded for each app you want in the flow (see the AUTOMATE guide).",
                "Open Command Deck → Workflows, then add the capabilities in that order.",
                "Check each step shows its own package and identity.",
                "Run the workflow, or expose it as a trigger so a button or a broadcast starts it.",
                "Read the journal afterwards: every step records succeeded or failed and the inverse that undoes it.",
            ),
            examplePhrase = "run the owned duo workflow",
            capabilityIds = listOf("fixture.banner.hide", "target.two.acknowledge"),
            runsOn = "the owned pair of test apps; the mechanism is not fixture-specific",
            limits = "Steps run in order on one device and stop at the first failure. It is not a background " +
                "service: the flow runs while the operator is watching, and anything it changes is journalled.",
            reversible = "Run the inverse the journal recorded for each step, or restore from APP CONTROL.",
        ),
        ModGuide(
            id = "mod.skip.ads",
            title = "Skip ads that offer a skip control",
            category = ModCategory.MODIFY,
            oneLiner = "Tap SKIP when a screen presents one. Nothing more is claimed.",
            whatItDoes = "Waits for the ad's skip control to appear using a fingerprint that names both its class " +
                "and its description, taps it, then asserts the screen actually moved on to content. If the screen " +
                "did not change, the capability reports failure instead of pretending it worked.",
            requirements = listOf(ModRequirement.BRIDGE),
            steps = listOf(
                "Switch on Settings → Accessibility → fR3k HUD.",
                "Open APP MODS → MODIFY → this guide and read the limits below.",
                "Open Command Deck → Workflows, enter the phrase below as the goal, and build a plan. Review the exact target before running.",
                "Watch the journal: the step succeeds only when the post-condition (content playing) is observed.",
                "If it reports failure, nothing was changed — do not retry blindly.",
            ),
            examplePhrase = "skip the ads",
            capabilityIds = listOf("fixture.ad.skip", "fixture.ad.mask", "fixture.ad.unmask"),
            runsOn = "the owned test fixture. It is NOT wired to the YouTube app",
            limits = "This is not ad removal. Non-skippable in-stream ads are unaffected, and no third-party app " +
                "has a qualified recipe in this build. Removing ads from an app you do not own would mean " +
                "re-signing and reinstalling someone else's app, and this build refuses that.",
            reversible = "Skipping is a tap; there is nothing to undo. If a mask was applied, run the unmask phrase.",
        ),
        ModGuide(
            id = "mod.appops",
            title = "Change a per-app permission Settings will not show you",
            category = ModCategory.MODIFY,
            oneLiner = "Read and set app-ops like WAKE_LOCK, RUN_IN_BACKGROUND, POST_NOTIFICATION.",
            whatItDoes = "Uses HUD's own privileged service to read an app-op, set a new mode, read it back to " +
                "confirm, and restore the previous value. Calls are typed operations with a fixed argument set, " +
                "not shell strings.",
            requirements = listOf(ModRequirement.ADB_BOOTSTRAP),
            steps = listOf(
                "Complete the one-time ADB bootstrap (see CONNECT → that guide).",
                "Open APP MODS → MODIFY → this guide; the card shows READY once the service answers.",
                "Choose the package and the app-op, for example WAKE_LOCK.",
                "Read the current mode first, then set the new one.",
                "Verify the read-back; HUD reports the observed value, not the requested one.",
                "Restore the original mode when you are finished.",
            ),
            examplePhrase = "deny wake lock for the owned fixture",
            capabilityIds = listOf(
                "fixture.policy.read_wake_lock",
                "fixture.policy.deny_wake_lock",
                "fixture.policy.allow_wake_lock",
            ),
            runsOn = "the owned test fixture; the service itself is app-agnostic",
            limits = "App-ops are not the same as runtime permissions, and not every op is settable on every " +
                "Android version. Where the platform refuses, HUD reports the refusal rather than a success.",
            reversible = "Set the op back to its previous mode — the capability records what it was.",
        ),
        ModGuide(
            id = "mod.root.extras",
            title = "Unlock the root-only extras",
            category = ModCategory.MODIFY,
            oneLiner = "Root-gated operations, reported as actually granted rather than assumed.",
            whatItDoes = "Starting the same HUD service from a root shell unlocks the operations the shell tier " +
                "refuses: listing an app's private directory names (never file contents) and disabling or " +
                "enabling a single component of one package.",
            requirements = listOf(ModRequirement.ADB_BOOTSTRAP, ModRequirement.ROOT),
            steps = listOf(
                "Get a root shell (adb root on a debuggable image, or a root terminal on the device).",
                "Run the app_process bootstrap command from APP CONTROL while HUD is running.",
                "Reopen APP MODS: the status banner should read ROOT.",
                "Run the root-gated capability you need and read the reported tier.",
                "Withdraw root when you are done: stop the service from APP CONTROL.",
            ),
            examplePhrase = "list the private directory names for the owned fixture",
            capabilityIds = emptyList(),
            runsOn = "any installed app, once root is genuinely held",
            limits = "Root is never assumed and never faked: HUD reports the tier it actually holds. Without " +
                "root these operations refuse with a tier message instead of silently failing. File contents are " +
                "never exposed — directory names only.",
            reversible = "Stop the privileged service; the tier drops back to disconnected.",
        ),
        ModGuide(
            id = "mod.ai.go",
            title = "Point the app at your OpenCode Go subscription",
            category = ModCategory.CONNECT,
            oneLiner = "Your Go key takes over from OpenRouter in every surface, immediately.",
            whatItDoes = "Stores your OpenCode Go key in this installation's encrypted store and routes every " +
                "chat surface — the HUD chat bubble, the mini browser, the shared agent session and the model " +
                "picker — to Go. Requests go to https://opencode.ai/zen/go/v1 with your key as a bearer token " +
                "and a routing session header; the key never leaves the device and is never written to a log.",
            requirements = emptyList(),
            steps = listOf(
                "Open SETTINGS → AI provider — which brain answers.",
                "Paste your OpenCode Go key and press SAVE KEY. The active line should immediately read " +
                    "'OpenCode Go (your key)'.",
                "Press TEST. ONLINE plus a model count means the key and the endpoint are accepted.",
                "Open the HUD chat bubble: the first line names the provider and the model actually answering.",
                "To go back, press REMOVE; the key rules re-evaluate at once and OpenRouter takes over again.",
                "If you also hold an OpenRouter key, Go still wins while your Go key is stored. Pin one " +
                    "provider explicitly in the preference row if you ever want the other one.",
            ),
            examplePhrase = "which model are you using",
            capabilityIds = emptyList(),
            runsOn = "this HUD installation, on this device",
            limits = "Only the Go models served on the OpenAI-compatible /chat/completions shape can carry " +
                "tool calls, which is what the chat and browser sessions need. Go also lists models on " +
                "/responses and /messages; those need a different client and are not offered here. A Go " +
                "subscription is billed by OpenCode, not by this app.",
            reversible = "Press REMOVE in SETTINGS; nothing else about the app depends on the key.",
        ),
        ModGuide(
            id = "mod.agent.access",
            title = "Drive all of it from your own agent",
            category = ModCategory.CONNECT,
            oneLiner = "A localhost MCP surface with closed-schema tools.",
            whatItDoes = "Exposes HUD's capabilities over MCP on localhost only, behind a per-start token. Your " +
                "own agent can list capabilities, read identities, render a plan for approval, execute an " +
                "approved plan, run a workflow, read the UI tree, read the journal, and read these guides.",
            requirements = emptyList(),
            steps = listOf(
                "Start the surface: START_MCP broadcast, or the button on the APP MODS screen.",
                "Read the port and token from the status line.",
                "Point your MCP client at http://127.0.0.1:<port> with the bearer token.",
                "Call tools/list, then tools/call. Nothing runs without the operator-approved flag where a " +
                    "write is involved.",
                "Stop the surface when you are done — it lives only as long as the process.",
            ),
            examplePhrase = "start the MCP surface and show me the port",
            capabilityIds = emptyList(),
            runsOn = "this device only; the socket binds to localhost",
            limits = "Localhost and token only — there is no remote listener and no wireless pairing UI yet. " +
                "Write tools still require explicit approval; the surface cannot be used to bypass that.",
            reversible = "Stop_MCP, or let the process end; the port closes with it.",
        ),
        ModGuide(
            id = "mod.ask.plain.english",
            title = "Ask for it in plain English",
            category = ModCategory.AUTOMATE,
            oneLiner = "Type what you want; HUD refuses rather than guesses.",
            whatItDoes = "Compiles a phrase into a plan by matching it against the registered capabilities' " +
                "declared trigger phrases, with token overlap only as a fallback. If nothing matches, it names " +
                "the closest capabilities and executes nothing.",
            requirements = listOf(ModRequirement.BRIDGE),
            steps = listOf(
                "Open Command Deck → Workflows → Build reviewable plan (or the MCP tool render_plan).",
                "Type what you want in your own words, for example 'hide the banner'.",
                "Read the plan HUD renders: the capability id, its app, and its identity.",
                "Approve it, or reword it if the match is not what you meant.",
                "Check the journal for what actually ran.",
            ),
            examplePhrase = "hide the banner",
            capabilityIds = listOf("fixture.banner.hide", "fixture.banner.show"),
            runsOn = "whatever is registered on this device",
            limits = "The model may only choose from the registry; it cannot invent a capability. An unknown " +
                "goal is refused with the closest matches named, never silently reinterpreted.",
            reversible = "The compiled plan's steps carry their own inverses; run the inverse to undo.",
        ),
        ModGuide(
            id = "mod.priv.bootstrap",
            title = "One-time setup: start HUD's privileged service",
            category = ModCategory.CONNECT,
            oneLiner = "No Shizuku, no LSPatch, no Termux — just one ADB command.",
            whatItDoes = "Starts HUD's own AIDL service from the installed HUD APK using app_process, from an " +
                "authorised ADB host, as UID 2000. It publishes the service through a provider that requires " +
                "the DUMP permission and a real shell or root caller, and it stops itself after 30 minutes or " +
                "when the HUD process dies.",
            requirements = listOf(ModRequirement.ADB_BOOTSTRAP),
            steps = listOf(
                "Connect an authorised ADB host to this device and confirm HUD is running.",
                "Open APP CONTROL and press SHOW AUTHORISED ADB BOOTSTRAP COMMAND.",
                "Run that command on the host. Keep the shell open — the service lives as long as it does.",
                "Return to APP MODS: the status banner should read SHELL (or ROOT if you used a root shell).",
                "Press STOP HUD PRIVILEGED SERVICE in APP CONTROL when you are done.",
            ),
            examplePhrase = "show me the privileged bootstrap command",
            capabilityIds = emptyList(),
            runsOn = "this HUD installation",
            limits = "Wireless ADB pairing is not integrated yet, and there is no reboot recovery: after a " +
                "reboot you run the command again. The service is read-only at the shell tier; the root tier " +
                "unlocks the extra operations listed in the root guide.",
            reversible = "Stop the service, or let the session expire.",
        ),
    )

    /** One card per registered capability, so every capability is reachable and explained. */
    fun capabilities(): List<ModGuide> = listOf(
        ModGuide(
            id = "cap.fixture.demo.open",
            title = "Open the owned test fixture",
            category = ModCategory.INSPECT,
            oneLiner = "Launches the test app's home screen and waits for its title.",
            whatItDoes = "Launches $FIXTURE via its own deeplink, then waits for the screen's title to appear. " +
                "It is the smallest possible capability: a launch and a wait, no taps and no writes.",
            requirements = listOf(ModRequirement.BRIDGE),
            steps = listOf(
                "Switch on Settings → Accessibility → fR3k HUD.",
                "Open Command Deck → Workflows, enter 'open the owned fixture' as the goal, build a plan, review and run it.",
                "Watch the fixture's home screen appear.",
                "Check the journal: the launch and the wait should both record OK.",
            ),
            examplePhrase = "open the owned fixture",
            capabilityIds = listOf("fixture.demo.open"),
            runsOn = FIXTURE,
            limits = "Owned test app only. It proves the bridge works; it changes nothing.",
            reversible = "Press back; nothing was written.",
        ),
        ModGuide(
            id = "cap.fixture.banner",
            title = "Hide or restore the owned fixture's banner",
            category = ModCategory.MODIFY,
            oneLiner = "Writes to the test app and proves it by looking for the banner.",
            whatItDoes = "Launches the fixture, sends an ordered broadcast protected by HUD's signature, then " +
                "asserts the banner is absent (hide) or present (show) and reports the target's PID back.",
            requirements = listOf(ModRequirement.BRIDGE),
            steps = listOf(
                "Switch on Settings → Accessibility → fR3k HUD.",
                "Open Command Deck → Workflows, enter 'hide the banner' as the goal, build a plan, review and run it.",
                "Check the journal says the banner is absent — HUD looked for it after the write.",
                "Build and review a plan for 'show the banner' to restore it and confirm it is back.",
                "Notice the PID in the receipt matches the running fixture — that is how a real write is proven.",
            ),
            examplePhrase = "hide the banner",
            capabilityIds = listOf("fixture.banner.hide", "fixture.banner.show"),
            runsOn = FIXTURE,
            limits = "Owned test app only, and only through its own signature-protected receiver. It is not a " +
                "general way to change a third-party app.",
            reversible = "Run the other phrase; each is the other's inverse.",
        ),
        ModGuide(
            id = "cap.fixture.ad.skip",
            title = "Skip the fixture's simulated ad",
            category = ModCategory.MODIFY,
            oneLiner = "Taps SKIP, then asserts content actually started playing.",
            whatItDoes = "Opens the fixture's simulated ad screen, waits for the skip control, taps it, then " +
                "asserts the status text reads CONTENT_PLAYING. Success requires the post-condition, not the tap.",
            requirements = listOf(ModRequirement.BRIDGE),
            steps = listOf(
                "Switch on Settings → Accessibility → fR3k HUD.",
                "Open Command Deck → Workflows, enter 'skip the ads' as the goal, build a plan, review and run it.",
                "Watch the screen: the skip control is tapped and the status should read CONTENT_PLAYING.",
                "Confirm the journal records the assertion as OK — that assertion, not the tap, is the proof.",
                "If it reports failure, nothing changed: read the failure rather than retrying blindly.",
            ),
            examplePhrase = "skip the ads",
            capabilityIds = listOf("fixture.ad.skip"),
            runsOn = FIXTURE,
            limits = "A simulated ad inside the owned test app. It demonstrates the mechanism; it does not " +
                "touch any real app's advertising.",
            reversible = "Nothing to undo — it is a tap in a test app.",
        ),
        ModGuide(
            id = "cap.fixture.ad.mask",
            title = "Mask or unmask the simulated ad control",
            category = ModCategory.MODIFY,
            oneLiner = "Draws a real overlay over the control and measures that it darkened.",
            whatItDoes = "Places an accessibility overlay exactly over the matched control's bounds, and reports " +
                "the measured bounds. Measured evidence: the covered region's brightness drops to near zero. " +
                "The unmask capability removes every mask HUD placed.",
            requirements = listOf(ModRequirement.BRIDGE),
            steps = listOf(
                "Switch on Settings → Accessibility → fR3k HUD.",
                "Type 'mask the ad' and watch the control disappear behind the overlay.",
                "Check the journal for the measured bounds of the covered region.",
                "Type 'unmask' to clear it and confirm the overlay count returns to zero.",
                "Masks disappear on their own if HUD's service stops — nothing is left drawn on your screen.",
            ),
            examplePhrase = "mask the ad",
            capabilityIds = listOf("fixture.ad.mask", "fixture.ad.unmask"),
            runsOn = FIXTURE,
            limits = "Cosmetic only: the control is covered, not removed, and nothing about the app changes. " +
                "The overlay lives only while HUD's service lives.",
            reversible = "Run 'unmask'; every mask HUD placed is removed.",
        ),
        ModGuide(
            id = "cap.fixture.policy",
            title = "Read, deny or restore the fixture's WAKE_LOCK app-op",
            category = ModCategory.MODIFY,
            oneLiner = "Typed app-op operation over the privileged service, with read-back.",
            whatItDoes = "Reads the WAKE_LOCK app-op for the fixture, sets it to deny or allow, and reports the " +
                "observed value afterwards. No shell string is involved — it is a fixed typed operation.",
            requirements = listOf(ModRequirement.ADB_BOOTSTRAP),
            steps = listOf(
                "Complete the one-time ADB bootstrap (CONNECT → start HUD's privileged service).",
                "Open this card and check it reports READY; if not, the banner shows what is missing.",
                "Type 'read the wake lock op' and note the current mode.",
                "Type 'deny wake lock', then read it again to confirm the observed change.",
                "Type 'allow wake lock' to restore the original mode.",
            ),
            examplePhrase = "deny wake lock",
            capabilityIds = listOf(
                "fixture.policy.read_wake_lock",
                "fixture.policy.deny_wake_lock",
                "fixture.policy.allow_wake_lock",
            ),
            runsOn = FIXTURE,
            limits = "Shell tier is required, and the operation is scoped to an app-op the platform exposes. " +
                "Refusals are reported as refusals.",
            reversible = "'allow wake lock' restores the default mode.",
        ),
        ModGuide(
            id = "cap.target.two.open",
            title = "Open the second owned target",
            category = ModCategory.COMBINE,
            oneLiner = "Launches the second test app and waits for its title.",
            whatItDoes = "Launches $FIXTURE_TWO via its deeplink and waits for its title. It exists so a " +
                "workflow can prove it crossed from one app to another.",
            requirements = listOf(ModRequirement.BRIDGE),
            steps = listOf(
                "Switch on Settings → Accessibility → fR3k HUD.",
                "Open Command Deck → Workflows, enter 'open the second target' as the goal, build a plan, review and run it.",
                "Confirm the journal records the launch and the wait as OK.",
                "Leave it open if you are about to run the cross-app flow (see COMBINE → fuse several apps).",
            ),
            examplePhrase = "open target two",
            capabilityIds = listOf("target.two.open"),
            runsOn = FIXTURE_TWO,
            limits = "Owned test app only. It is the second half of a cross-app flow, not a way to reach " +
                "a third-party app.",
            reversible = "Press back; nothing was written.",
        ),
        ModGuide(
            id = "cap.target.two.acknowledge",
            title = "Press ACKNOWLEDGE on the second target",
            category = ModCategory.COMBINE,
            oneLiner = "A real tap in a second app, asserted by the text that appears.",
            whatItDoes = "Launches the second target, waits for its acknowledgement button, taps it, then " +
                "asserts the screen now shows 'acknowledged'. This is the second half of the cross-app workflow.",
            requirements = listOf(ModRequirement.BRIDGE),
            steps = listOf(
                "Switch on Settings → Accessibility → fR3k HUD.",
                "Open Command Deck → Workflows, enter 'acknowledge the second target' as the goal, build a plan, review and run it.",
                "Confirm the assertion in the journal — that, not the tap, is the proof.",
                "Run the COMBINE guide to chain this behind a step in another app.",
            ),
            examplePhrase = "acknowledge the second target",
            capabilityIds = listOf("target.two.acknowledge"),
            runsOn = FIXTURE_TWO,
            limits = "Owned test app only. It demonstrates a cross-app step; it is not a way to drive an app " +
                "you have not bound to an identity.",
            reversible = "Relaunch the app; its state is test-only.",
        ),
    )

    fun all(context: Context): List<ModGuide> = (if (CapabilityCatalogue.labMode(context)) all() else releaseProcedures()) + ((context.applicationContext as? com.mcpintelligence.fr3k.Fr3kApplication)?.capabilityHost?.registry() ?: CapabilityCatalogue.discover(context)).all()
        .map { it.descriptor }.filter { it.id.startsWith("app.") }.map { cap ->
            val mapped = cap.steps.any { it is com.mcpintelligence.fr3k.capability.Step.Click }
            val privileged = cap.steps.any { it is com.mcpintelligence.fr3k.capability.Step.PrivilegedOp }
            ModGuide(id = "guide.${cap.id}", title = cap.summary,
                category = if (privileged) ModCategory.MODIFY else ModCategory.AUTOMATE,
                oneLiner = "Selected installed app · exact version, signer and APK fingerprint",
                whatItDoes = "${cap.summary}. The workflow binds to ${cap.identity.label}; an installation change refuses the old plan.",
                requirements = if (privileged) listOf(ModRequirement.ADB_BOOTSTRAP) else listOf(ModRequirement.BRIDGE),
                steps = listOf("App Control → select an installed package → ENABLE SELECTED APP CONTROLS. For screen controls, use MAP SELECTED APP HOME first.",
                    "Command Deck → Workflows → select ${cap.id}.",
                    "Review the identity and action, tick approval and run. Inspect the Journal receipt."),
                examplePhrase = cap.summary, capabilityIds = listOf(cap.id), runsOn = cap.identity.label,
                limits = "Android enforces shell permissions. App-op modes can change notifications, background behaviour or overlays. This does not grant root, bypass another app's signer, or provide a compatible patch recipe.",
                reversible = if (mapped) "No automatic inverse is known for this screen control. Review its consequences; return with Back where appropriate. Use the target app to reverse any persistent change." else if (privileged) "For a write, the Journal records the previous mode when confirmed; restore that mode with the corresponding control. A read changes nothing." else "Opening the app changes no persistent setting; return with Back.")
        }

    fun all(): List<ModGuide> = procedures() + capabilities()

    /** Every capability id any guide mentions. Used by the coverage test. */
    fun coveredCapabilityIds(): Set<String> = all().flatMap { it.capabilityIds }.toSet()

    fun coverage(capabilityIds: Collection<String>): List<String> =
        capabilityIds.filterNot { it in coveredCapabilityIds() }.sorted()

    /** Compact JSON for the MCP surface, so an agent sees the same walkthroughs a person does. */
    fun toJson(guides: List<ModGuide>): org.json.JSONArray = org.json.JSONArray().also { array ->
        guides.forEach { guide ->
            array.put(org.json.JSONObject().apply {
                put("id", guide.id)
                put("title", guide.title)
                put("category", guide.category.label)
                put("oneLiner", guide.oneLiner)
                put("whatItDoes", guide.whatItDoes)
                put("requirements", org.json.JSONArray(guide.requirements.map { it.label }))
                put("steps", org.json.JSONArray(guide.steps))
                put("examplePhrase", guide.examplePhrase)
                put("capabilityIds", org.json.JSONArray(guide.capabilityIds))
                put("runsOn", guide.runsOn)
                put("limits", guide.limits)
                put("reversible", guide.reversible)
            })
        }
    }
}
