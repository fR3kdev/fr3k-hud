package com.mcpintelligence.fr3k.capability

/** Allow-listed per-app operations. Anything not here cannot be requested by a plan or a model. */
object PrivilegedOps {
    val ALLOWED = listOf(
        "WAKE_LOCK",
        "RUN_IN_BACKGROUND",
        "RUN_ANY_IN_BACKGROUND",
        "START_FOREGROUND",
        "VIBRATE",
        "POST_NOTIFICATION",
        "SYSTEM_ALERT_WINDOW",
        "GET_USAGE_STATS",
    )
    val MODES = listOf("default", "allow", "ignore", "deny")

    fun requireAllowed(op: String) {
        require(op in ALLOWED) { "op '$op' is not allow-listed" }
    }

    fun requireMode(mode: String) {
        require(mode in MODES) { "mode '$mode' is not one of $MODES" }
    }
}

data class PolicyRule(val packageGlob: String, val op: String, val mode: String) {
    init {
        require(packageGlob.matches(Regex("[A-Za-z0-9_.*]+"))) { "invalid package glob" }
        PrivilegedOps.requireAllowed(op)
        PrivilegedOps.requireMode(mode)
    }
}

data class PolicyChange(val packageName: String, val op: String, val from: String, val to: String) {
    val inverse: PolicyChange get() = PolicyChange(packageName, op, to, from)
}

data class PolicyDocument(val version: Int, val rules: List<PolicyRule>) {
    init {
        require(version >= 1)
        require(rules.isNotEmpty()) { "policy needs at least one rule" }
    }

    companion object {
        fun matches(glob: String, packageName: String): Boolean {
            if (glob == "*") return true
            if (!glob.contains('*')) return glob == packageName
            // Regex.escape would wrap the whole glob (including '*') in \Q..\E, so build it per segment.
            val regex = Regex("^" + glob.split("*").joinToString(".*") { Regex.escape(it) } + "$")
            return regex.matches(packageName)
        }
    }
}

object PolicyPlanner {
    /** Minimal diff between observed op modes and the desired document. */
    fun diff(
        document: PolicyDocument,
        observed: Map<Pair<String, String>, String>,
        installedPackages: List<String>,
    ): List<PolicyChange> {
        val changes = ArrayList<PolicyChange>()
        installedPackages.forEach { pkg ->
            document.rules.filter { PolicyDocument.matches(it.packageGlob, pkg) }.forEach { rule ->
                val current = observed[pkg to rule.op] ?: "default"
                if (current != rule.mode) changes.add(PolicyChange(pkg, rule.op, current, rule.mode))
            }
        }
        return changes
    }
}
