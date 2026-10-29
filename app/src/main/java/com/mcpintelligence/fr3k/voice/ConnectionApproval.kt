package com.mcpintelligence.fr3k.voice

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Which same-signature apps the operator has allowed to drive HUD voice.
 *
 * The manifest already restricts binding to apps signed with this key; this is the operator's own
 * consent on top of that, so a companion cannot start speaking or asking questions just because it
 * shares the certificate. Decisions are per package + signing certificate digest, so a different
 * build signed with another key has to ask again.
 */
object ConnectionApproval {
    private const val PREFS = "fr3k_hud_prefs"
    private const val KEY = "approved_voice_clients"

    fun entry(packageName: String, certificateSha256: String) = "$packageName|$certificateSha256"

    fun approved(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet())?.toSet().orEmpty()

    fun isApproved(context: Context, packageName: String, certificateSha256: String): Boolean =
        entry(packageName, certificateSha256) in approved(context)

    fun approve(context: Context, packageName: String, certificateSha256: String) {
        val next = approved(context) + entry(packageName, certificateSha256)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, next).commit()
    }

    fun deny(context: Context, packageName: String, certificateSha256: String) {
        val next = approved(context) - entry(packageName, certificateSha256)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, next).commit()
    }

    /** Signing certificate digest of an installed package, or null when it cannot be read. */
    fun certificateSha256(context: Context, packageName: String): String? = runCatching {
        val manager = context.packageManager
        val info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching null
        MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun label(context: Context, packageName: String): String = runCatching {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
