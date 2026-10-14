package com.mcpintelligence.fr3k.ui.integrations

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.mcpintelligence.fr3k.hud.HudOverlayService

class WirelessDebuggingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!android.provider.Settings.canDrawOverlays(this)) {
            android.widget.Toast.makeText(this,"Allow HUD to display over other apps, then reopen Wireless debug to pair beside Android Settings.",android.widget.Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:$packageName")))
        } else startForegroundService(Intent(this,HudOverlayService::class.java).setAction(HudOverlayService.ACTION_OPEN_WIRELESS))
        finish()
    }
}
