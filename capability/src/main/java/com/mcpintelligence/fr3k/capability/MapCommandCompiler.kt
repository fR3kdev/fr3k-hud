package com.mcpintelligence.fr3k.capability

/** Compiles recorded screens into deterministic callable commands. No model involved. */
object MapCommandCompiler {
    fun compile(map: AppMap, registryIdPrefix: String = "map"): List<CapabilityDescriptor> =
        map.screens.map { screen ->
            val prefix = map.identity.packageName.substringAfterLast(".")
            val risk = if (screen.reachVia.any { it is Step.PrivilegedOp }) RiskClass.PRIVILEGED else RiskClass.REVERSIBLE_WRITE
            CapabilityDescriptor(
                id = "$registryIdPrefix.$prefix.${slug(screen.name)}",
                plane = if (risk == RiskClass.PRIVILEGED) Plane.PRIVILEGED else Plane.BRIDGE,
                risk = risk,
                summary = "Open ${screen.name.replace('_', ' ')} in ${map.identity.packageName}",
                identity = map.identity,
                steps = screen.reachVia + listOf(Step.Observe(400)),
                parameters = emptyMap(),
                sourceBundle = "appmap:${map.identity.fingerprint.take(12)}",
            )
        }

    private fun slug(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), ".").trim('.').ifEmpty { "screen" }
}
