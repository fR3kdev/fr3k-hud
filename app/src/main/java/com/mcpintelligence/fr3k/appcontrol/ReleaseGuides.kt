package com.mcpintelligence.fr3k.appcontrol

import com.mcpintelligence.fr3k.capability.*

/** Production journeys. Fixture walkthroughs exist only in the instrumented capability lab. */
fun appModGuides(): List<ModGuide> = listOf(
    releaseGuide("mod.discovery", "Find app-specific improvements", ModCategory.INSPECT,
        "Analyse immutable APK copies on this phone, inspect resource/DEX evidence and find improvements tied to this exact build.", emptyList(),
        listOf("APP MODS → FIND APP IMPROVEMENTS → choose APK or installed package.", "Read coverage, inferred purpose and evidence. Enter what you want to improve, then find local suggestions.", "Optionally confirm the public owner/repository and research requests, or request AI ideas from a redacted summary."),
        "Public requests show demand, not missing features or executable compatibility. APKs and full code stay local; AI summaries are bounded to32KiB.",
        "Analysis changes no target app. Originals remain archived until explicitly deleted."),
    releaseGuide("mod.preparation", "Preview and prepare exact app changes", ModCategory.MODIFY,
        "Select resource text/colour/layout, DEX constants or reviewed helper/hook templates and approve an exact source-bound preparation on this phone.", emptyList(),
        listOf("Choose executable suggestions, enter replacement values or a listed helper ID, then select installation mode.", "Preview the complete changes, source fingerprint, digest, consequences and recovery. Conflicting or unsupported changes are refused.", "Approve exact changes and prepare the copy. Wait for PREPARED with a verified output hash; process death requires fresh review."),
        "Preparation proves static checks only. Helpers have exact method/register constraints. Hook recipes are version-bound; no arbitrary scripts or generated Java run.",
        "Cancel preparation or review the retained original. No installed app changes until Android installation."),
    releaseGuide("mod.install.restore", "Install and restore a prepared APK set", ModCategory.MODIFY,
        "Use Android's reviewed package-install sessions for a prepared base/split APK set, then verify the installed artifact and test the changed behavior.", emptyList(),
        listOf("Open the durable job → Review in Android installer. Grant installation permission to HUD when required, then review again.", "Confirm Android installation. Check installed artifact hashes/signer, then test the desired behavior and record manual observations separately.", "For recovery, review archived original restoration. Original APKs remain available after uninstall; no uninstall occurs automatically."),
        "Only exact qualified profiles support side-by-side installation. Switching signers may require uninstall/data loss. APK archives do not back up private app data.",
        "Review original restoration in Android's installer. Reset generated preference state through the target app's own data controls if required.")
)

fun releaseProcedures(): List<ModGuide> = appModGuides() + listOf(
    releaseGuide("mod.inspect.app","Inspect an installed app",ModCategory.INSPECT,
        "Read exact installed version, signer and APK hashes without changing the app.",emptyList(),
        listOf("App Control → BROWSE INSTALLED APPS, then choose a target.","Press INSPECT SELECTED APP — READ ONLY.","Read package, version, signer and per-APK hashes; preserve them for a later review."),
        "Inspection does not read private app data or prove a module is compatible.","Nothing changes; there is nothing to undo."),
    releaseGuide("mod.controls","Control an installed app",ModCategory.MODIFY,
        "Register launch and eight app-op controls for an app you choose.",emptyList(),
        listOf("App Control → browse installed apps and choose a target.","Press ENABLE SELECTED APP CONTROLS.","Command Deck → Workflows: choose an action, inspect its target, review and run."),
        "Android grants determine which operations succeed. Each plan binds to the exact installed build.","Restore the previous app-op mode from Journal before removing the app's controls."),
    releaseGuide("mod.maps","Turn screen controls into workflows",ModCategory.AUTOMATE,
        "Record accessible home-screen controls and create fingerprinted actions.",listOf(ModRequirement.BRIDGE),
        listOf("Select an installed app in App Control.","Press MAP SELECTED APP HOME; HUD opens the app and reads its accessible controls without pressing them.","Return to Workflows, review a recorded action and run it against the matching build."),
        "Custom canvas UI may expose no nodes. Password fields, destructive labels and ambiguous matches are refused. An APK change invalidates the map.","Recorded screen actions may have no automatic inverse; inspect consequences and reverse changes in the target app."),
    releaseGuide("mod.modify.own.app","Patch an installed app with a compatible module",ModCategory.MODIFY,
        "Prepare, sign, verify and review a modified single-APK build with an original archive.",emptyList(),
        listOf("Choose an installed target → PATCH SELECTED APP — IMPORT MODULE & REVIEW.","Import a signed compatible Xposed module APK and press PREPARE SELECTED APP COPY.","Review package, fingerprint, hashes and signing/data-loss warnings before opening Android's installer.","Check actual behaviour after installation; use REVIEW ARCHIVED ORIGINAL to restore."),
        "Split targets require a split-aware recipe and are refused. Compatibility depends on the module. Re-signing can break sign-in; uninstalling loses private data. HUD never uninstalls automatically.","Review and reinstall the hash-verified archived original. Switching signing identities can require uninstalling and lose app data."),
    releaseGuide("mod.workflows","Combine actions across apps",ModCategory.COMBINE,
        "Build a reviewed sequence of capabilities with exact targets and progress receipts.",emptyList(),
        listOf("Enable controls or record maps for the apps you want to combine.","Workflows → choose capabilities in the required order, or build a reviewable plan from a goal.","Read identities, requirements and undo information; approve and run.","Inspect the execution progress and Journal receipts."),
        "Unknown or ambiguous goals are refused. A failed step stops the sequence. Execution is not an atomic transaction across apps.","Use each completed step's recorded inverse where available; actions without an inverse require manual recovery."),
    releaseGuide("mod.shell","Start shell control on this phone",ModCategory.CONNECT,
        "Pair Android Wireless debugging and start HUD's own shell service without a computer.",emptyList(),
        listOf("Connections → WIRELESS DEBUG; allow HUD to display over apps.","Enable Android Developer options → Wireless debugging on Wi-Fi → Pair device with pairing code.","Move the HUD panel beside Settings; enter the device IP, pairing port and six-digit code, then Pair.","Enter the different connection port from Wireless debugging's main page; press CONNECT & START HUD SHELL and check verified UID2000."),
        "Requires Android 11+ Wi-Fi debugging. Pairing is not proof the shell service started. Reconnect after reboot or service expiry. Shell does not grant root.","Stop the shell in App Control. Forget HUD's pairing identity and remove fR3kHUD under Android's paired devices."),
    releaseGuide("mod.root","Use root where the device grants it",ModCategory.CONNECT,
        "The same HUD service exposes root-only operations when launched with UID0.",emptyList(),
        listOf("App Control → SHOW AUTHORISED ADB BOOTSTRAP COMMAND.","Run the shown HUD app_process command from an authorised root shell on your rooted device.","Refresh Connections and verify ROOT · UID0 before requesting root-only operations."),
        "HUD cannot root a device or grant itself UID0. Wireless debugging normally provides UID2000. An installed manager does not prove root.","Stop the privileged service in App Control. Reverse individual changes using their actual receipts."),
    releaseGuide("mod.ai.go","Connect your AI provider",ModCategory.CONNECT,
        "Configure the provider, tool-capable model and consent profile used by chat and text actions.",emptyList(),
        listOf("Settings → choose a consent profile and provider preference.","Save an OpenCode Go or OpenRouter credential; select a supported model.","Press TEST and inspect actual health; send a synthetic question before sharing personal context."),
        "Remote requests need network and a valid subscription/key, and may incur provider costs. LOCAL ONLY blocks remote AI egress.","Remove the credential or select LOCAL ONLY. Already-sent requests cannot be recalled."),
    releaseGuide("mod.agent","Connect your own agent through MCP",ModCategory.CONNECT,
        "Expose the same registered capabilities and guides on localhost with a bearer token.",emptyList(),
        listOf("Command Deck → Agent → start MCP and inspect the local port.","Deliberately reveal the token and configure your local client.","List capabilities/guides, render a plan, review the exact targets, then request execution.","Inspect Journal receipts and stop MCP when finished."),
        "Localhost only. The token authorises this run; do not share it. Ten tools use closed schemas and cannot invent capabilities.","Stop MCP to end the surface. Undo completed actions individually where a receipt contains a real inverse.")
)

private fun releaseGuide(id:String,title:String,category:ModCategory,description:String,requirements:List<ModRequirement>,steps:List<String>,limits:String,undo:String)=
    ModGuide(id,title,category,description,description,requirements,steps,title,emptyList(),"Installed apps and connections chosen on this device",limits,undo)
