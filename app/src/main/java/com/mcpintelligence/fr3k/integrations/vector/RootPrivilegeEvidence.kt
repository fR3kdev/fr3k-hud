package com.mcpintelligence.fr3k.integrations.vector

/** Accept only the direct output of the bounded, app-originated `id` probe. */
object RootPrivilegeEvidence {
    fun isVerified(exitCode: Int, output: String): Boolean =
        exitCode == 0 && output.trim().matches(Regex("uid=0(?:\\(root\\))?(?:\\s+[^\\r\\n]+)?"))
}
