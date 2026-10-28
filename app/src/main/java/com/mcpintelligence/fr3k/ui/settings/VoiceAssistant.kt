package com.mcpintelligence.fr3k.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.ui.Fr3kDimens
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kPanel
import com.mcpintelligence.fr3k.ui.voice.VoiceHub

internal enum class VoiceMode { ANDROID, XIAOZHI }

/** Voice entry point. Android system voice is the default; Xiaozhi is an explicit opt-in. */
internal object VoiceModePreference {
    private const val PREFS = "fr3k_hud_prefs"
    private const val KEY = "voice_mode"
    fun read(context: Context): VoiceMode =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) == VoiceMode.XIAOZHI.name) {
            VoiceMode.XIAOZHI
        } else VoiceMode.ANDROID
    fun write(context: Context, mode: VoiceMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
    }
}

@Composable
internal fun VoiceAssistantPanel() {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(VoiceModePreference.read(context)) }
    Fr3kPanel(title = "voice assistant", subtitle = "android voice by default · xiaozhi optional") {
        Column(verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
                Button(
                    onClick = { mode = VoiceMode.ANDROID; VoiceModePreference.write(context, mode) },
                    enabled = mode != VoiceMode.ANDROID,
                    modifier = Modifier.weight(1f),
                ) { Text("ANDROID VOICE") }
                Button(
                    onClick = { mode = VoiceMode.XIAOZHI; VoiceModePreference.write(context, mode) },
                    enabled = mode != VoiceMode.XIAOZHI,
                    modifier = Modifier.weight(1f),
                ) { Text("XIAOZHI") }
            }
            Text(
                when (mode) {
                    VoiceMode.ANDROID -> "Speaks with the installed Android speech engine and uses the shared HUD agent session."
                    VoiceMode.XIAOZHI -> "Talks to voice.fr3k.dev directly, with its own activation and audio channel."
                },
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
            when (mode) {
                VoiceMode.ANDROID -> VoiceHub()
                VoiceMode.XIAOZHI -> XiaozhiSetupPanel()
            }
        }
    }
}
