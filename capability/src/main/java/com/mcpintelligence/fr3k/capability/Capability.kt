package com.mcpintelligence.fr3k.capability

enum class Plane { PATCH, BRIDGE, PRIVILEGED }

enum class RiskClass { OBSERVE, REVERSIBLE_WRITE, REINSTALL, PRIVILEGED }

enum class ParamType { BOOL, TEXT, INT }

/** Permanent refusals. A capability whose id or summary trips one of these cannot be constructed. */
val FORBIDDEN_TOKENS = listOf(
    "premium", "unlock_premium", "subscription", "drm", "widevine",
    "play_integrity", "integrity_spoof", "signature_bypass", "sig_bypass",
    "payment", "billing_bypass", "entitlement", "keygen", "license_crack",
)

data class CapabilityDescriptor(
    val id: String,
    val plane: Plane,
    val risk: RiskClass,
    val summary: String,
    val identity: AppIdentity,
    val steps: List<Step>,
    val parameters: Map<String, ParamType> = emptyMap(),
    val sourceBundle: String = "local",
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_.-]{2,95}"))) { "invalid capability id" }
        require(summary.isNotBlank()) { "capability needs a human summary" }
        require(steps.isNotEmpty()) { "capability needs at least one step" }
        // Separator-insensitive so "play.integrity" and "play_integrity" are both refused.
        val probe = (id + " " + summary).lowercase().replace(Regex("[^a-z0-9]"), "")
        require(FORBIDDEN_TOKENS.none { probe.contains(it.replace("_", "").replace("-", "")) }) {
            "capability '$id' is refused by doctrine and cannot be registered"
        }
        require(parameters.keys.all { it.matches(Regex("[a-z][a-z0-9_]{0,31}")) }) { "invalid parameter name" }
    }

    val fingerprint: String get() = identity.fingerprint

    /** Bind arguments into the step template. Unknown or untyped arguments are refused. */
    fun instantiate(args: Map<String, String>): List<Step> {
        val unknown = args.keys - parameters.keys
        require(unknown.isEmpty()) { "unknown parameters: $unknown" }
        for ((name, type) in parameters) {
            val value = args[name] ?: continue
            when (type) {
                ParamType.BOOL -> require(value == "true" || value == "false") { "$name must be true or false" }
                ParamType.INT -> require(value.toIntOrNull() != null) { "$name must be an integer" }
                ParamType.TEXT -> Unit
            }
        }
        return steps.map { step ->
            val json = step.toJson()
            substitute(json, args)
            Step.fromJson(json)
        }
    }

    private fun substitute(node: Any?, args: Map<String, String>) {
        when (node) {
            is org.json.JSONObject -> {
                for (key in node.keys().asSequence().toList()) {
                    val value = node.get(key)
                    if (value is String) node.put(key, expand(value, args)) else substitute(value, args)
                }
            }
            is org.json.JSONArray -> for (i in 0 until node.length()) {
                val value = node.get(i)
                if (value is String) node.put(i, expand(value, args)) else substitute(value, args)
            }
        }
    }

    private fun expand(value: String, args: Map<String, String>): String {
        val matches = Regex("\\$([a-z][a-z0-9_]*)").findAll(value).toList()
        var out = value
        for (match in matches) {
            val name = match.groupValues[1]
            require(parameters.containsKey(name)) { "step references undeclared parameter '$name'" }
            val replacement = args[name] ?: throw IllegalArgumentException("missing parameter '$name'")
            out = out.replace(match.value, replacement)
        }
        return out
    }
}
