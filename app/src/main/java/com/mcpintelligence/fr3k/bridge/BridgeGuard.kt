package com.mcpintelligence.fr3k.bridge

/** Pure decision logic for what the bridge may touch. Unit-testable without Android. */
object BridgeGuard {
    private val neverActOn = setOf(
        "com.android.systemui",
        "com.android.settings",
        "android",
        "com.google.android.gms",
        "com.android.vending",
        "com.mcpintelligence.fr3k.hud",
    )

    /** System surfaces and our own process are never automated. */
    fun mayActOn(targetPackage: String?, activePlanPackages: Set<String>): Boolean {
        if (targetPackage == null) return false
        if (targetPackage in neverActOn) return false
        return activePlanPackages.contains(targetPackage)
    }

    /** Destructive controls are refused unless the step was explicitly approved for this run. */
    fun isDestructive(label: String?): Boolean {
        val text = label?.lowercase() ?: return false
        val markers = listOf(
            "buy", "purchase", "pay", "subscribe", "checkout", "confirm payment",
            "send", "delete", "uninstall", "transfer", "call", "dial",
        )
        return markers.any { text.contains(it) }
    }
}
