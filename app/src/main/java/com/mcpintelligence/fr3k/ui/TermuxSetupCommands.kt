package com.mcpintelligence.fr3k.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mcpintelligence.fr3k.ui.Fr3kPalette

/** Stable shell commands: no pairing code, credential, bearer or untrusted user data. */
object TermuxSetupCommands {
    const val INSTALL_HELPER = "mkdir -p ~/.local/bin && install -m 700 ~/storage/downloads/fr3k-termux-adb.py ~/.local/bin/fr3k-termux-adb"
    const val SETUP = "~/.local/bin/fr3k-termux-adb setup"
    const val STATUS = "~/.local/bin/fr3k-termux-adb status"
    const val RECONNECT = "~/.local/bin/fr3k-termux-adb connect"
    const val SETTINGS = "~/.local/bin/fr3k-termux-adb settings"
    const val STORAGE = "termux-setup-storage"
    const val UPDATE_ADB = "pkg install -y android-tools libprotobuf libc++"
    const val EXPORT_FILENAME = "fr3k-termux-adb.py"

    /**
     * Copies the audited, bundled helper into Android Downloads for an explicit Termux install.
     * No remote shell, automatic pairing, or private app files are exposed.
     */
    fun exportHelper(context: Context): String {
        check(Build.VERSION.SDK_INT >= 29) { "Automatic helper export requires Android 10+" }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val existing = resolver.query(
            collection, arrayOf(MediaStore.Downloads._ID),
            MediaStore.Downloads.DISPLAY_NAME + "=? AND " +
                MediaStore.Downloads.RELATIVE_PATH + "=?",
            arrayOf(EXPORT_FILENAME, Environment.DIRECTORY_DOWNLOADS + "/"), null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        val uri = if (existing != null) {
            android.content.ContentUris.withAppendedId(collection, existing)
        } else {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, EXPORT_FILENAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/x-python")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            }
            resolver.insert(collection, values) ?: error("Android rejected Downloads export")
        }
        resolver.openOutputStream(uri, "wt")?.use { sink ->
            context.assets.open("termux/" + EXPORT_FILENAME).use { it.copyTo(sink) }
        } ?: error("Could not write helper")
        return "Exported to Downloads/" + EXPORT_FILENAME
    }

    fun copy(context: Context, command: String): Boolean {
        if (command.isBlank()) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("FR3K Termux command", command))
        return true
    }
}

@Composable
fun CopyTermuxCommand(context: Context, title: String, command: String, id: String) {
    var copied by remember(command) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(title, color = Fr3kPalette.Accent)
        Text(command, fontFamily = FontFamily.Monospace, color = Fr3kPalette.Text)
        OutlinedButton(
            onClick = { copied = TermuxSetupCommands.copy(context, command) },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "copy-termux-" + id
            },
        ) {
            Text(if (copied) "COPIED · PASTE IN TERMUX" else "COPY COMMAND")
        }
    }
}
