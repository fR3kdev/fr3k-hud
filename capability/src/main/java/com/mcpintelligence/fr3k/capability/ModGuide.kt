package com.mcpintelligence.fr3k.capability

/**
 * A walkthrough for one thing a person can actually do with this build.
 *
 * A guide is not marketing copy: it names the exact requirements, the steps the operator performs, the
 * capability that executes, how to undo it, and what it cannot do. [limits] is mandatory, because the
 * difference between "this skips an ad that offers a skip control" and "this removes ads" is the whole
 * product promise, and a guide that blurs it is worse than no guide.
 */
data class ModGuide(
    val id: String,
    val title: String,
    val category: ModCategory,
    /** One line, shown on the collapsed card. */
    val oneLiner: String,
    /** What it really does, in plain language. */
    val whatItDoes: String,
    val requirements: List<ModRequirement>,
    /** The walkthrough, in the order the operator performs it. */
    val steps: List<String>,
    /** What to type into HUD chat (or say to the overlay) to run it. */
    val examplePhrase: String,
    /** Capability ids that actually execute. Empty only for pure setup procedures. */
    val capabilityIds: List<String>,
    /** Which apps this applies to, stated honestly. */
    val runsOn: String,
    /** Mandatory honesty line. */
    val limits: String,
    /** How the operator reverses it. */
    val reversible: String,
) {
    init {
        require(id.isNotBlank()) { "a guide needs an id" }
        require(title.isNotBlank()) { "guide $id needs a title" }
        require(oneLiner.isNotBlank()) { "guide $id needs a one-line summary" }
        require(whatItDoes.isNotBlank()) { "guide $id needs an explanation" }
        require(steps.size >= 2) { "guide $id needs a real walkthrough, not one step" }
        require(examplePhrase.isNotBlank()) { "guide $id needs an example phrase" }
        require(runsOn.isNotBlank()) { "guide $id must say what it runs on" }
        require(limits.isNotBlank()) { "guide $id must state its limits" }
        require(reversible.isNotBlank()) { "guide $id must say how to undo it" }
    }
}

enum class ModCategory(val label: String, val blurb: String) {
    INSPECT("INSPECT", "Look before you touch: identity, screens, permissions"),
    MODIFY("MODIFY", "Change how an app behaves"),
    AUTOMATE("AUTOMATE", "Make a manual chore run itself"),
    COMBINE("COMBINE", "Fuse several apps into one flow"),
    CONNECT("CONNECT", "Wire HUD into your own tools and agents"),
}

/** What a guide needs from the device before it can work. */
enum class ModRequirement(val label: String, val howTo: String) {
    BRIDGE(
        "HUD accessibility service",
        "Settings → Accessibility → fR3k HUD, switch it on. This is what lets HUD read screens and tap for you.",
    ),
    ADB_BOOTSTRAP(
        "one-time ADB bootstrap",
        "On Android 11+, open App Control → WIRELESS DEBUG to pair and start on this phone. Alternatively, from an authorised computer run the app_process command shown in APP CONTROL → 'SHOW AUTHORISED ADB BOOTSTRAP COMMAND' while HUD is running.",
    ),
    ROOT(
        "a root shell",
        "Run that same command from a root shell (adb root, or a root terminal on the device) instead of UID 2000.",
    ),
    OWNED_TARGET(
        "an app you installed and own",
        "This works on an APK you installed yourself (your own build, or your own copy). Replacing it re-signs it, which breaks sign-in for apps that pin their original signer.",
    ),
}

/** Live device state. Evaluated when the screen opens; never cached across launches. */
data class ModEnvironment(
    val bridgeBound: Boolean = false,
    val privilegedShell: Boolean = false,
    val privilegedRoot: Boolean = false,
) {
    fun satisfied(requirement: ModRequirement): Boolean = when (requirement) {
        ModRequirement.BRIDGE -> bridgeBound
        ModRequirement.ADB_BOOTSTRAP -> privilegedShell || privilegedRoot
        ModRequirement.ROOT -> privilegedRoot
        // Owning a target is the operator's decision, not something the device can observe.
        ModRequirement.OWNED_TARGET -> true
    }
}

enum class ModReadiness(val label: String) {
    READY("READY"),
    NEEDS_SETUP("NEEDS SETUP"),
}

object ModGuideStatus {
    fun missing(guide: ModGuide, environment: ModEnvironment): List<ModRequirement> =
        guide.requirements.filterNot { environment.satisfied(it) }

    fun readiness(guide: ModGuide, environment: ModEnvironment): ModReadiness =
        if (missing(guide, environment).isEmpty()) ModReadiness.READY else ModReadiness.NEEDS_SETUP

    /** Ready guides first, then by category, then by title — a stable, reviewable order. */
    fun order(guides: List<ModGuide>, environment: ModEnvironment): List<ModGuide> =
        guides.sortedWith(
            compareBy(
                { readiness(it, environment) != ModReadiness.READY },
                { it.category.ordinal },
                { it.title.lowercase() },
            ),
        )
}
