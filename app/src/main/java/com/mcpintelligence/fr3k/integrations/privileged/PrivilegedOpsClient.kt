package com.mcpintelligence.fr3k.integrations.privileged

import android.content.Context
import com.mcpintelligence.fr3k.appcontrol.AndroidIdentityReader
import com.mcpintelligence.fr3k.capability.AppIdentity
import com.mcpintelligence.fr3k.capability.PrivilegedOps

/** Typed client for the HUD-owned service. Callers pass an op name and a mode, never a command. */
class PrivilegedOpsClient(private val context: Context) {

    val tier: String?
        get() = runCatching { HudPrivilegedBridge.serviceOrNull()?.capabilityTier }.getOrNull()

    fun connected(): Boolean = HudPrivilegedBridge.serviceOrNull() != null

    fun allowedOps(): List<String> =
        runCatching { HudPrivilegedBridge.serviceOrNull()?.listAllowedOps() }.getOrNull() ?: PrivilegedOps.ALLOWED

    fun appOpMode(packageName: String, op: String): String {
        PrivilegedOps.requireAllowed(op)
        val service = HudPrivilegedBridge.serviceOrNull() ?: error("privileged service is not connected")
        return service.getAppOp(packageName, op).getString("mode") ?: "unknown"
    }

    data class Change(val packageName: String, val op: String, val before: String, val after: String)

    fun setAppOp(packageName: String, op: String, mode: String): Change {
        PrivilegedOps.requireAllowed(op)
        PrivilegedOps.requireMode(mode)
        val service = HudPrivilegedBridge.serviceOrNull() ?: error("privileged service is not connected")
        val result = service.setAppOp(packageName, op, mode)
        return Change(
            result.getString("package") ?: packageName,
            result.getString("op") ?: op,
            result.getString("before") ?: "unknown",
            result.getString("after") ?: "unknown",
        )
    }

    /** Root tier only: names and sizes of an app's private directory, never file contents. */
    fun listPrivateDir(packageName: String): List<String> {
        val service = HudPrivilegedBridge.serviceOrNull() ?: error("privileged service is not connected")
        return service.listPrivateDir(packageName).getStringArray("names")?.toList() ?: emptyList()
    }

    fun setComponentEnabled(packageName: String, component: String, enabled: Boolean): Boolean {
        val service = HudPrivilegedBridge.serviceOrNull() ?: error("privileged service is not connected")
        return service.setComponentEnabled(packageName, component, enabled).getBoolean("enabled", enabled)
    }

    fun identityOf(packageName: String): AppIdentity = AndroidIdentityReader.of(context, packageName)

    /** The lambda the step executor uses for Step.PrivilegedOp. "read" is a read, not an op mode. */
    suspend fun execute(packageName: String, op: String, mode: String): String = when (mode) {
        "read" -> "op=$op mode=${appOpMode(packageName, op)} tier=$tier"
        else -> setAppOp(packageName, op, mode).let { "op=$op ${it.before}->${it.after} tier=$tier" }
    }
}
