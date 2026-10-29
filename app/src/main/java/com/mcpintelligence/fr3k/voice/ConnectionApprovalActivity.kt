package com.mcpintelligence.fr3k.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.ui.Fr3kPalette

/**
 * Explicit operator approval before another same-signature app may drive voice, speech or the shared
 * agent. Shown as a dialog over whatever screen is current; denies by default (back press = deny).
 */
class ConnectionApprovalActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LABEL = "label"
        const val EXTRA_CERTIFICATE = "certificate"
        const val EXTRA_ACTION = "action"

        fun intent(context: Context, packageName: String, label: String, certificate: String, action: String): Intent =
            Intent(context, ConnectionApprovalActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_CERTIFICATE, certificate)
                .putExtra(EXTRA_ACTION, action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL) ?: packageName
        val certificate = intent.getStringExtra(EXTRA_CERTIFICATE).orEmpty()
        val action = intent.getStringExtra(EXTRA_ACTION) ?: "voice"
        val shortCert = certificate.take(16)
        setContent {
            Surface(color = Fr3kPalette.Bg, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("CONNECTION REQUEST", color = Fr3kPalette.Warn, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text(
                        "\"$label\" wants to $action through FR3K HUD.",
                        color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                    Text("package: $packageName", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("signer: $shortCert…", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(
                        "Same signing key as this HUD. Approving lets it start listening, ask the shared agent, and speak replies until you withdraw it. Nothing is shared yet.",
                        color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    )
                    Button(
                        onClick = { decide(true) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Ok),
                    ) { Text("ALLOW \"$label\"") }
                    Button(
                        onClick = { decide(false) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("DENY") }
                }
            }
        }
    }

    private fun decide(allow: Boolean) {
        if (allow) ConnectionApproval.approve(this, intent.getStringExtra(EXTRA_PACKAGE).orEmpty(), intent.getStringExtra(EXTRA_CERTIFICATE).orEmpty())
        else ConnectionApproval.deny(this, intent.getStringExtra(EXTRA_PACKAGE).orEmpty(), intent.getStringExtra(EXTRA_CERTIFICATE).orEmpty())
        setResult(if (allow) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }
}
