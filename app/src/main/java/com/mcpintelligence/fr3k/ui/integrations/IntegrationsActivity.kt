package com.mcpintelligence.fr3k.ui.integrations

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.integrations.privileged.HudPrivilegedBridge
import kotlinx.coroutines.*

/** Connections used by the actual product. No external privilege or patch manager is required. */
class IntegrationsActivity : ComponentActivity() {
    private var panel: com.mcpintelligence.fr3k.ui.FloatingPanel?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,32,32,32); setBackgroundColor(0xff080B18.toInt()) }
        fun label(value:String)=TextView(this).apply { text=value; textSize=14f; setTextColor(0xffE6ECF5.toInt()); setPadding(0,20,0,20) }
        fun button(title:String, action:()->Unit) { body.addView(Button(this).apply { text=title; minHeight=(48*resources.displayMetrics.density).toInt(); setOnClickListener { action() } }) }
        body.addView(label("fR3k // CONNECTIONS").apply { textSize=24f;setTextColor(0xffC5A0FF.toInt()) })
        body.addView(label("HUD controls apps through its own accessibility bridge, embedded patch engine and paired shell service. No separate privilege or patch manager is required. Each action remains subject to the Android permissions actually granted on this device."))
        val status=label("Checking shell service…");body.addView(status)
        fun refresh() { status.text=runCatching { HudPrivilegedBridge.serviceOrNull()?.let { "${it.capabilityTier} CONNECTED · UID ${it.serverUid}" } ?: "SHELL DISCONNECTED · pair on this phone to start" }.getOrDefault("SHELL DISCONNECTED") }
        refresh()
        button("WIRELESS DEBUG — PAIR & START WITHOUT A COMPUTER") { startActivity(Intent(this,WirelessDebuggingActivity::class.java)) }
        button("REFRESH CONNECTION STATUS") { refresh() }
        body.addView(label("Shell: Android 11+ Wireless debugging pairs HUD on this phone. Restart after reboot or service expiry. Root: run the same HUD app_process bootstrap from an authorised root shell. Root availability is verified by the service UID, never by an installed manager."))
        button("APP CONTROL — TARGETS, PATCHING & ROOT BOOTSTRAP") { startActivity(Intent(this,com.mcpintelligence.fr3k.ui.appcontrol.AppControlActivity::class.java)) }
        body.addView(label("TERMUX · OPTIONAL TERMINAL\nThe main Termux app executes commands in its own sandbox. Enable allow-external-apps=true in Termux properties, grant HUD ‘Run commands in Termux environment’, then test below. Termux:API is optional. This does not grant Android shell or root."))
        val termux=Fr3kApplication.get().termuxBridge
        button("OPEN TERMUX") { val intent=packageManager.getLaunchIntentForPackage("com.termux");if(intent!=null) startActivity(intent) else startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/termux/termux-app/releases"))) }
        button("TERMUX GRANT INSTRUCTIONS") {
            panel?.dismiss(); val text=label(termux.grantInstructions()).apply { setTextIsSelectable(true) }
            panel=com.mcpintelligence.fr3k.ui.FloatingPanel(this,"Termux setup",ScrollView(this).apply { addView(text) }).show()
        }
        button("SMOKE-TEST: echo hi from fr3k") {
            status.text="Testing Termux…"
            lifecycleScope.launch {
                val result=withContext(Dispatchers.IO) { termux.runRaw("echo hi-from-fr3k",30000) }
                status.text="Termux exit=${result.exitCode}\n${result.stdout}\n${result.stderr}"
            }
        }
        button("OPEN HUD ANDROID PERMISSIONS") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))) }
        button("PROVIDER CONNECTION & TEST") { startActivity(Intent(this,com.mcpintelligence.fr3k.ui.settings.SettingsActivity::class.java)) }
        button("CLOSE") { finish() }
        setContentView(ScrollView(this).apply { clipToPadding=false;addView(body);setOnApplyWindowInsetsListener { view,insets -> val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets } })
    }
    override fun onDestroy() { panel?.dismiss();super.onDestroy() }
}
