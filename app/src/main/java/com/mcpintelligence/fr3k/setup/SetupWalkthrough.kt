package com.mcpintelligence.fr3k.setup

/**
 * One setup requirement, with the exact Android screen that satisfies it and what to say.
 *
 * [blockedByRoot] marks items that simply cannot be done on some devices: when the operator marks
 * one skipped it is remembered and never asked again, instead of nagging on every launch.
 */
data class SetupItem(
    val id: String,
    val title: String,
    val detail: String,
    val spoken: String,
    val optional: Boolean = false,
    val needsDeveloperOptions: Boolean = false,
    val skippable: Boolean = true,
    /** Ordered sub-steps for items that cannot be done in one screen, e.g. installing Shizuku. */
    val steps: List<String> = emptyList(),
)

/** What the device currently reports, gathered by the app layer. */
data class SetupFacts(
    val microphone: Boolean,
    val notifications: Boolean,
    val overlay: Boolean,
    val tts: Boolean,
    val recogniser: Boolean,
    val provider: Boolean,
    val developerOptions: Boolean = false,
    val wirelessDebugging: Boolean = false,
    val termuxInstalled: Boolean = false,
    val termuxApi: Boolean = false,
    val termuxBoot: Boolean = false,
    val termuxStorage: Boolean = false,
    val shizukuRunning: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val voiceTuned: Boolean = false,
)

enum class SetupStatus { TODO, DONE, SKIPPED }

/**
 * Chooses what to ask next and remembers what the operator has already settled.
 *
 * Pure: persistence and Android intents live outside, so ordering, skipping and "don't ask again"
 * can be tested directly.
 */
object SetupWalkthrough {

    val items: List<SetupItem> = listOf(
        SetupItem(
            id = "microphone",
            title = "Microphone",
            detail = "FR3K needs the microphone to hear you.",
            spoken = "First, allow the microphone. Tap allow microphone and choose Allow.",
        ),
        SetupItem(
            id = "tts",
            title = "Android voice",
            detail = "Install a text-to-speech engine and your language, so the assistant can speak.",
            spoken = "Next, open Android voice settings, choose a speech engine and download your language.",
        ),
        SetupItem(
            id = "recogniser",
            title = "Speech recognition",
            detail = "Install the speech recognition service so FR3K can understand words.",
            spoken = "Next, open speech input settings and install the speech recognition service.",
        ),
        SetupItem(
            id = "voice_tuning",
            title = "Reply voice and speed",
            detail = "Pick the speaking voice and rate, so replies sound right to you.",
            spoken = "Your voice works. Now choose the reply voice and speed you like.",
            skippable = true,
        ),
        SetupItem(
            id = "provider",
            title = "Model provider key",
            detail = "Choose Groq, OpenCode Go or OpenRouter, then validate your API key. Unsure? Start with Groq's rate-limited free tier.",
            spoken = "Next, choose your model provider. If you are unsure, I recommend Groq's free tier. OpenCode Go and OpenRouter are also available.",
            optional = false,
            skippable = false,
        ),
        SetupItem(
            id = "notifications",
            title = "Notifications",
            detail = "Needed for the HUD's foreground service and status.",
            spoken = "Next, allow notifications so the HUD can run in the background.",
        ),
        SetupItem(
            id = "overlay",
            title = "Display over other apps",
            detail = "The floating orb and chat bubble need the overlay permission.",
            spoken = "Next, allow display over other apps, so the orb can float above your screen.",
        ),
        SetupItem(
            id = "developer_options",
            title = "Developer options",
            detail = "Enable developer options: Settings, About phone, then tap Build number seven times.",
            spoken = "Developer options are off. Open settings, go to About phone, and tap Build number seven times.",
            optional = true,
            skippable = true,
        ),
        SetupItem(
            id = "wireless_debugging",
            title = "Wireless debugging",
            detail = "Enable Wireless debugging in developer options to pair Shizuku without a cable.",
            spoken = "Now open developer options and turn on wireless debugging, then pair it from Shizuku.",
            optional = true,
            needsDeveloperOptions = true,
            skippable = true,
        ),
        SetupItem(
            id = "termux",
            title = "Termux",
            detail = "Termux is not installed. Install it from F-Droid (not Play Store): https://f-droid.org/packages/com.termux/",
            spoken = "Next, Termux is not installed. Install Termux from F Droid, not the Play Store, then come back.",
            optional = true,
            skippable = true,
        ),
        SetupItem(
            id = "termux_api",
            title = "Termux:API",
            detail = "Termux:API lets FR3K use device features from scripts. Install from F-Droid: https://f-droid.org/packages/com.termux.api/",
            spoken = "Then install Termux A P I from F Droid, so scripts can reach device features.",
            optional = true,
            skippable = true,
        ),
        SetupItem(
            id = "termux_boot",
            title = "Termux:Boot",
            detail = "Termux:Boot starts your scripts after a reboot. Install from F-Droid: https://f-droid.org/packages/com.termux.boot/",
            spoken = "Then install Termux Boot from F Droid, so your scripts start again after a reboot.",
            optional = true,
            skippable = true,
        ),
        SetupItem(
            id = "termux_storage",
            title = "Termux storage",
            detail = "In Termux run: termux-setup-storage, then allow the storage prompt.",
            spoken = "In Termux, run termux setup storage, and allow the storage prompt when it appears.",
            optional = true,
            skippable = true,
        ),
        SetupItem(
            id = "shizuku",
            title = "Shizuku (optional)",
            detail = "Shizuku adds privileged shell actions. Everything else works without it.",
            spoken = "Shizuku is optional. It only adds privileged shell actions, so you can skip it.",
            optional = true,
            skippable = true,
            steps = listOf(
                "Download Shizuku from its GitHub releases page (github.com/RikkaApps/Shizuku/releases) and install the APK.",
                "Open Shizuku and start the service. Without root, use Wireless debugging: enable it in developer options first.",
                "In Shizuku, tap Pairing and follow the pairing code prompt from Wireless debugging.",
                "Back in Shizuku, grant access to Termux so scripts can use it.",
                "Then grant access to FR3K HUD so privileged actions work.",
                "Return here and press DONE; FR3K will verify Shizuku is running.",
            ),
        ),
        SetupItem(
            id = "battery",
            title = "Battery optimisation",
            detail = "Exempt FR3K from battery optimisation so the overlay and voice survive.",
            spoken = "Finally, exempt FR3K from battery optimisation, so the HUD is not killed in the background.",
            optional = true,
            skippable = true,
        ),
    )

    /** First unfinished item that is actually relevant on this device. */
    fun next(progress: Map<String, SetupStatus>, facts: SetupFacts): SetupItem? =
        items.firstOrNull { item ->
            (item.id == "provider" || progress[item.id] != SetupStatus.DONE) &&
                (item.id == "provider" || progress[item.id] != SetupStatus.SKIPPED) &&
                !satisfied(item, facts, progress) &&
                !neutralised(item, progress, facts)
        }

    /** True when the device already satisfies the item, so it should never be asked. */
    fun satisfied(item: SetupItem, facts: SetupFacts, progress: Map<String, SetupStatus>): Boolean = when (item.id) {
        "microphone" -> facts.microphone
        "tts" -> facts.tts
        "voice_tuning" -> facts.voiceTuned || !facts.tts
        "recogniser" -> facts.recogniser
        "provider" -> facts.provider
        "notifications" -> facts.notifications
        "overlay" -> facts.overlay
        "developer_options" -> facts.developerOptions
        // Asking for wireless debugging while developer options are off would be a dead end.
        "wireless_debugging" -> !facts.developerOptions || facts.wirelessDebugging
        "termux" -> facts.termuxInstalled
        "termux_api" -> facts.termuxApi
        "termux_boot" -> facts.termuxBoot
        "termux_storage" -> facts.termuxStorage
        "shizuku" -> facts.shizukuRunning
        "battery" -> facts.batteryUnrestricted
        else -> false
    }

    /** Developer-option children are pointless once that step has been skipped. */
    private fun neutralised(item: SetupItem, progress: Map<String, SetupStatus>, facts: SetupFacts): Boolean =
        item.needsDeveloperOptions &&
            (!facts.developerOptions || progress["developer_options"] == SetupStatus.SKIPPED)

    /** Remaining items, in order, that still need the operator. Used for the spoken overview. */
    fun pending(progress: Map<String, SetupStatus>, facts: SetupFacts): List<SetupItem> = buildList {
        val working = progress.toMutableMap()
        var remainingFacts = facts
        while (true) {
            val item = next(working, remainingFacts) ?: break
            add(item)
            working[item.id] = SetupStatus.DONE
            if (item.id == "provider") remainingFacts = remainingFacts.copy(provider = true)
        }
    }

    /** A short spoken plan: what is left and how many steps. */
    fun spokenPlan(progress: Map<String, SetupStatus>, facts: SetupFacts): String? {
        val remaining = pending(progress, facts)
        if (remaining.isEmpty()) return null
        val head = if (remaining.size == 1) "One setup step is left." else "${remaining.size} setup steps are left."
        return head + " " + remaining.first().spoken
    }

    /** Skipping is only offered where it is meaningful; blocked items stay remembered. */
    fun canSkip(item: SetupItem): Boolean = item.skippable && item.optional
}
