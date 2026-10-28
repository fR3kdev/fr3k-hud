package com.mcpintelligence.fr3k.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.integrations.xiaozhi.realXiaozhiVoiceAdapter
import com.mcpintelligence.fr3k.ui.Fr3kBadge
import com.mcpintelligence.fr3k.ui.Fr3kDimens
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kPanel

/** Live Xiaozhi voice configuration. The endpoint is real; enrollment status is shown honestly. */
internal object XiaozhiSetup {
    const val endpoint = "https://voice.fr3k.dev/xiaozhi/ota/"
    const val status = "USER-CONSENTED"
    val instructions = listOf(
        "Bootstrap endpoint: $endpoint",
        "Identity: app-private random device ID + client UUID, generated on this device. It is a virtual identity, not a hardware MAC address.",
        "Discovery runs only after you tick consent and press CONNECT; nothing is contacted in the background.",
        "Activation: if the server returns a code, enter it in the service console, then press CONNECT again.",
        "Reply audio: Android system voice by default; switch to Xiaozhi server audio to hear the server voice.",
    )
}

@Composable
internal fun XiaozhiSetupPanel() {
    val context = LocalContext.current
    val adapter = remember { realXiaozhiVoiceAdapter(context) }
    DisposableEffect(adapter) { onDispose { runCatching { adapter.close() } } }
    Fr3kPanel(title = "Xiaozhi voice", subtitle = "voice.fr3k.dev · explicit consent") {
        Column(verticalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingSm)) {
            Fr3kBadge(text = XiaozhiSetup.status, color = Fr3kPalette.Warn)
            XiaozhiSetup.instructions.forEach { instruction ->
                Text(instruction, color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            XiaozhiVoiceControls(adapter = adapter)
        }
    }
}
