package com.mcpintelligence.fr3k.core

import com.mcpintelligence.fr3k.protocol.Capability
import com.mcpintelligence.fr3k.protocol.Capabilities
import com.mcpintelligence.fr3k.protocol.CapabilityTier

/**
 * GPS command set (§25). Commands declare capability requirements; the registry
 * filters them out when the location plugin isn't running. The plugin registers
 * the capabilities at start.
 */
class GpsPlugin(private val action: (suspend (String) -> CommandResult)? = null) : Fr3kPlugin {

    override val pluginId: String = "fr3k.integrations.gps"
    override val displayName: String = "GPS"
    override val version: String = "0.1.0"

    override fun capabilities(): List<Capability> = listOf(
        Capability(
            id = Capabilities.LOCATION_CURRENT,
            displayName = "Current location",
            description = "Read the current GPS fix",
            tier = CapabilityTier.TIER_1,
            requiredPermissions = listOf("android.permission.ACCESS_FINE_LOCATION"),
        ),
        Capability(
            id = Capabilities.LOCATION_WAYPOINT,
            displayName = "Send waypoint",
            description = "Send the current fix as a waypoint",
            tier = CapabilityTier.TIER_1,
        ),
        Capability(
            id = Capabilities.LOCATION_SHARE,
            displayName = "Share location",
            description = "Share current location via the share sheet",
            tier = CapabilityTier.TIER_1,
        ),
    )

    override fun commands(): List<Fr3kCommand> = listOf(
        CopyLocationCommand(action),
        WaypointCommand(action),
        ShareLocationCommand(action),
    )

    override suspend fun start() {}
    override suspend fun stop() {}
}

/** Copy current coords to clipboard. V1 returns a synthetic string from context. */
class CopyLocationCommand(private val action: (suspend (String) -> CommandResult)? = null) : Fr3kCommand {
    override val id = "location.copy"
    override val title = "Copy coordinates"
    override val description = "Copy current lat/lon as text"
    override val requiredCapabilities = setOf(Capabilities.LOCATION_CURRENT)
    override val keywords = setOf("copy", "coords", "latlon", "gps")
    override val pluginId = "fr3k.integrations.gps"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        if (!context.hasAll(requiredCapabilities)) return CommandResult.Failed("Missing location capability")
        return action?.invoke("copy") ?: CommandResult.Failed("Location action unavailable; no GPS backend is configured. Nothing copied or sent.")
    }
}

/** Send a waypoint via the active transport (mesh first, else share). */
class WaypointCommand(private val action: (suspend (String) -> CommandResult)? = null) : Fr3kCommand {
    override val id = "location.waypoint"
    override val title = "Send waypoint"
    override val description = "Dispatch current fix as a waypoint"
    override val requiredCapabilities = setOf(Capabilities.LOCATION_WAYPOINT)
    override val keywords = setOf("waypoint", "gps", "send")
    override val pluginId = "fr3k.integrations.gps"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        if (!context.hasAll(requiredCapabilities)) return CommandResult.Failed("Missing location capability")
        return action?.invoke("waypoint") ?: CommandResult.Failed("Location action unavailable; no GPS backend is configured. Nothing copied or sent.")
    }
}

/** Share location via the system share sheet. */
class ShareLocationCommand(private val action: (suspend (String) -> CommandResult)? = null) : Fr3kCommand {
    override val id = "location.share"
    override val title = "Share location"
    override val description = "Share current location via share sheet"
    override val requiredCapabilities = setOf(Capabilities.LOCATION_SHARE)
    override val keywords = setOf("share", "gps", "location")
    override val pluginId = "fr3k.integrations.gps"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        if (!context.hasAll(requiredCapabilities)) return CommandResult.Failed("Missing location capability")
        return action?.invoke("share") ?: CommandResult.Failed("Location action unavailable; no GPS backend is configured. Nothing copied or sent.")
    }
}