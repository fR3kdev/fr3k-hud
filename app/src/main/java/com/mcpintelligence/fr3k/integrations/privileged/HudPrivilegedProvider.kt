package com.mcpintelligence.fr3k.integrations.privileged

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.mcpintelligence.fr3k.privileged.IHudPrivilegedService

/** OS permission plus Binder caller UID gate. No model/caller supplied uid is trusted. */
class HudPrivilegedProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != "attach") throw UnsupportedOperationException("Unknown method")
        val uid = Binder.getCallingUid()
        if (uid != 2000 && uid != 0) throw SecurityException("Shell/root publisher required")
        val binder = extras?.getBinder("service") ?: throw IllegalArgumentException("Missing service")
        HudPrivilegedBridge.attach(IHudPrivilegedService.Stub.asInterface(binder))
        return Bundle().apply {
            putBoolean("attached", true)
            putBinder("clientLifetime", Binder())
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
