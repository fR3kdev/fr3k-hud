package com.mcpintelligence.fr3k.integrations.shizuku

// No raw Binder transaction numbers: this legacy executor is deliberately
// unsupported until a qualified typed service is connected.

/**
 * Policy-scoped Shizuku executor — replaces the raw `Parcel.transact(0x0A)`
 * path in [ShizukuAdapter.shellCommand] with a narrow, typed set of
 * operations. The plan §8: "Reject raw shell strings; allow only typed
 * operations initially." No arbitrary `sh -c`, no package deletion, no
 * security-setting changes, no restricted targets.
 *
 * This keeps Shizuku's elevated privilege strictly bounded: the only way
 * to reach system-level capability is through an [Allowed] operation whose
 * target passes [policyTargetsApproved]. Everything a plugin or the UI
 * needs must be expressed as one of these typed requests.
 */
object ShizukuCommandExecutor {

    /** The single repository-owned package `InstallApprovedApk` may hit. */
    const val FIXTURE_PACKAGE = "com.mcpintelligence.fr3k.patchfixture"

    /**
     * Outcome of a policy check for one operation. [Allowed] carries a
     * prototype command we can safely hand to Shizuku; [Denied] carries a
     * human-readable reason and is never executed.
     */
    sealed class Operation {
        val safe: Boolean get() = this is Allowed
        data class Allowed(val kind: Kind, val target: String) : Operation()
        data class Denied(val reason: String) : Operation()

        enum class Kind {
            GetPackageInfo,
            ListPackageSplits,
            InstallApprovedApk,
            UninstallTestFixture,
            ReadSystemSetting,
        }

        companion object {
            /** Map a raw request + arg to a policy decision. */
            fun from(request: String, arg: String): Operation =
                when (request) {
                    "GetPackageInfo", "ListPackageSplits" ->
                        if (isPackageName(arg)) {
                            Allowed(if (request == "GetPackageInfo") Kind.GetPackageInfo else Kind.ListPackageSplits, arg)
                        } else Denied("invalid package name")
                    "InstallApprovedApk" -> Denied(
                        "installation requires staged APK identity, signer and explicit approval; a path is insufficient")
                    "UninstallTestFixture" ->
                        if (arg == FIXTURE_PACKAGE) {
                            Allowed(Kind.UninstallTestFixture, arg)
                        } else {
                            Denied("UninstallTestFixture only allowed on owned fixture $FIXTURE_PACKAGE")
                        }
                    "ReadSystemSetting" ->
                        if (isReadableSystemSetting(arg)) {
                            Allowed(Kind.ReadSystemSetting, arg)
                        } else {
                            Denied("non-readable system setting: $arg")
                        }
                    else -> Denied("unknown or shell operation rejected: '$request'")
                }
        }
    }

    /** True if [pkg] is the owned patch fixture or a benign target. */
    fun policyTargetsApproved(pkg: String): Boolean {
        if (pkg == FIXTURE_PACKAGE) return true
        // Deny banking/payment/credential/authenticator/security/DRM/system.
        val deniedAny = DENIED_TARGET_MARKERS.any { marker -> pkg.contains(marker, ignoreCase = true) }
            || pkg in SYSTEM_CRITICAL_PACKAGES
        return !deniedAny && isPackageName(pkg)
    }

    private val DENIED_TARGET_MARKERS = listOf(
        "bank", "pay", "wallet", "password", "browser",
        "authenticator", "2fa", "otp", "credential", "drm",
        "vpn", "security", "devicepolicy", "devicepolicycontroller",
        "play", "gms", "integrity", "samsung", "miui",
    )

    private val SYSTEM_CRITICAL_PACKAGES = setOf(
        "android", "com.android.systemui", "com.android.settings",
        "com.android.launcher3", "com.google.android.gms",
        "com.google.android.gsf", "com.android.vending",
        "com.google.android.apps.authenticator2",
    )

    /** Read-only settings only (global/secure/system). Never a writer. */
    private fun isPackageName(value: String): Boolean =
        value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))

    private fun isReadableSystemSetting(arg: String): Boolean =
        arg.matches(Regex("settings:(global|secure|system):[A-Za-z0-9_]+"))

    // ---------- executor (uses typed operations only) ----------

    /**
     * Run a [Operation.Allowed] against the live Shizuku binder. Returns
     * a small provenance object: stdout/stderr version of what the
     * operation observed, exit code, duration ms, and the operation kind.
     * Never accepts a raw shell string.
     */
    @Suppress("UNUSED_PARAMETER")
    fun execute(binder: android.os.IBinder, op: Operation.Allowed): ShResult =
        ShResult("", "Unsupported: no qualified typed Shizuku execution service is connected",
            1, 0, "unsupported:${op.kind.name}")

    data class ShResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long,
        val provenance: String,
    )

}