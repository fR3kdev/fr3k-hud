package com.mcpintelligence.fr3k.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class TermuxCommandCopyDeviceTest {
    @Test fun copyIsLiteralAndHelperExportsWithoutCredentials() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        app.startActivity(android.content.Intent(app, com.mcpintelligence.fr3k.ui.onboarding.OnboardingActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(700)
        val command = TermuxSetupCommands.SETUP
        assertTrue(TermuxSetupCommands.copy(app, command))
        val board = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(command, board.primaryClip!!.getItemAt(0).coerceToText(app).toString())
        for (value in listOf(TermuxSetupCommands.SETUP, TermuxSetupCommands.STATUS,
                TermuxSetupCommands.RECONNECT, TermuxSetupCommands.INSTALL_HELPER)) {
            assertFalse("No commands should contain an inline pairing code", Regex("\\b\\d{6}\\b").containsMatchIn(value))
            assertFalse(value.contains("Bearer "))
        }
        val outcome = TermuxSetupCommands.exportHelper(app)
        assertTrue(outcome.contains("fr3k-termux-adb.py"))
        val uri = app.contentResolver.query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Downloads._ID),
            android.provider.MediaStore.Downloads.DISPLAY_NAME + "=?",
            arrayOf(TermuxSetupCommands.EXPORT_FILENAME), null).use { c ->
            checkNotNull(c)
            assertTrue(c.moveToFirst())
            android.content.ContentUris.withAppendedId(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
        }
        val extracted = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val original = app.assets.open("termux/fr3k-termux-adb.py").use { it.readBytes() }
        assertArrayEquals("Exported helper must be byte-identical", original, extracted)
        assertTrue(String(extracted).contains("HUD pairing remains separate"))
    }
}
