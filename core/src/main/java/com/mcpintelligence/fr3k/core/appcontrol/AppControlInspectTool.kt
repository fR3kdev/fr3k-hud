package com.mcpintelligence.fr3k.core.appcontrol

import com.mcpintelligence.fr3k.core.tools.*

class AppControlInspectTool(private val inspect: suspend (String) -> TargetIdentity) : AgentTool {
    override val id = "app.control.inspect"
    override val displayName = "Inspect selected app build"
    override val description = "Reads only the selected installed package identity, signer and APK split hashes; never private app data"
    override val capability = "app.control.read"
    override val requiresNetwork = false
    override suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult {
        val pkg = args.require("package")
        require(validPackage(pkg)) { "invalid package name" }
        val t = inspect(pkg)
        require(t.packageName == pkg) { "Inspector returned a different target" }
        val fields = mapOf("package" to t.packageName, "versionCode" to t.versionCode.toString(),
            "signerSha256" to t.signerSha256, "splitSha256" to t.splitSha256.joinToString(","),
            "applied" to "false")
        return ToolResult.Success(fields.entries.joinToString("\n") { "${it.key}=${it.value}" }, fields)
    }
}
