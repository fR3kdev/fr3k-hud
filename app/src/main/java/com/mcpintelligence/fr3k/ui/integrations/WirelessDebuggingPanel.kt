package com.mcpintelligence.fr3k.ui.integrations

import android.content.*
import android.widget.*
import android.view.*
import com.mcpintelligence.fr3k.integrations.privileged.WirelessDebugging
import com.mcpintelligence.fr3k.hud.overlays.*
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.ui.TermuxSetupCommands
import kotlinx.coroutines.*

class WirelessDebuggingPanel(private val context: Context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val overlayHost=OverlayHost(context,context.getSystemService(WindowManager::class.java))
    private var root: View?=null
    fun hide() { root?.let { overlayHost.remove(it) }; root=null; scope.cancel() }
    fun show() {
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(pad,pad,pad,pad); setBackgroundColor(0xff080B18.toInt()) }
        fun label(value: String) = TextView(context).apply { text=value; textSize=14f; setTextColor(0xffE6ECF5.toInt()); setPadding(0,8,0,8) }
        fun input(title: String, value: String = "", number: Boolean = false) = EditText(context).apply {
            hint=title; contentDescription=title; setText(value); setSingleLine(); setTextColor(0xffE6ECF5.toInt()); setHintTextColor(0xffB0BCD5.toInt())
            if(number) inputType=android.text.InputType.TYPE_CLASS_NUMBER
            body.addView(this)
        }
        body.addView(label("WIRELESS DEBUGGING · Android 11+\n1. Enable Developer options → Wireless debugging on this phone and join Wi-Fi.\n2. Open ‘Pair device with pairing code’. Move this panel beside that sheet. Enter its IP, pairing port and six-digit code.\n3. Pair, then use the DIFFERENT connection port on the main Wireless debugging page. Start HUD shell.\nPair again after forgetting this HUD identity. Reconnect after reboot or service expiry (30 minutes). No computer or companion manager required. Shell is Android UID 2000, not root."))
        val prefs=context.getSharedPreferences("wireless-endpoint",android.content.Context.MODE_PRIVATE)
        val host=input("Device IP address",prefs.getString("host","127.0.0.1")!!)
        val pairing=input("Pairing port",number=true)
        val pin=input("Six-digit pairing code",number=true).apply { transformationMethod=android.text.method.PasswordTransformationMethod.getInstance() }
        val connection=input("Connection port",prefs.getString("port","")!!,true)
        val status=label("Not connected. Pairing alone does not start the HUD shell service.").apply { contentDescription="wireless-status" }
        data class Values(val address: String, val pairingPort: String, val code: String, val connectionPort: String)
        fun action(title:String, work:suspend (Values)->String) {
            body.addView(Button(context).apply {
                text=title
                setOnClickListener {
                    val values = Values(host.text.toString().trim(), pairing.text.toString(), pin.text.toString(), connection.text.toString())
                    isEnabled=false; status.text="Working…"
                    scope.launch {
                        try { status.text=withContext(Dispatchers.IO) { work(values) } }
                        catch(e:CancellationException) { throw e }
                        catch(e:Exception) { status.text="Not completed: ${e.message ?: e.javaClass.simpleName}" }
                        finally { isEnabled=true; pin.text.clear() }
                    }
                }
            })
        }
        action("PAIR THIS HUD") { values ->
            val address=values.address; val port=values.pairingPort.toIntOrNull() ?: error("Enter pairing port")
            val code=values.code; require(port in 1..65535); require(code.matches(Regex("[0-9]{6}"))) { "Enter a six-digit pairing code" }
            WirelessDebugging(context.applicationContext).use { check(it.pair(address,port,code)) { "Pairing rejected. Generate a fresh code and check the pairing port." } }
            "PAIRED. Enter the main Wireless debugging connection port, then start HUD shell."
        }
        action("CONNECT & START HUD SHELL") { values ->
            val address=values.address; val port=values.connectionPort.toIntOrNull() ?: error("Enter connection port")
            WirelessDebugging(context.applicationContext).use { client -> client.startHud(context.applicationContext,address,port).also {
                prefs.edit().putString("host",address).putString("port",port.toString()).apply()
            } }
        }
        action("FORGET HUD PAIRING IDENTITY") {
            WirelessDebugging(context.applicationContext).use { it.forget() }
            "HUD identity removed. Also remove fR3kHUD under Android Wireless debugging → Paired devices. Existing shell service is unchanged; stop it in App Control."
        }
        body.addView(Button(context).apply { text="OPEN DEVELOPER OPTIONS"; setOnClickListener {
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { status.text="Enable Developer options by tapping Build number seven times in About phone." }
        } })
        body.addView(label("SECOND PAIRING · TERMUX (SEPARATE FROM HUD)"))
        body.addView(label("HUD may already be paired; do not forget or re-pair its identity. Open a NEW Android pairing-code sheet for Termux, then use its pairing port and six-digit code. The regular connection port is different."))
        fun copyCommand(title:String, command:String) {
            body.addView(label(title + "\n" + command))
            body.addView(Button(context).apply {
                text="COPY COMMAND"
                contentDescription="copy-termux-" + title.lowercase().replace(Regex("[^a-z0-9]+"), "-")
                setOnClickListener {
                    TermuxSetupCommands.copy(context,command)
                    text="COPIED · PASTE IN TERMUX"
                }
            })
        }
        copyCommand("Install Termux ADB package", TermuxSetupCommands.UPDATE_ADB)
        copyCommand("Allow Downloads access (once)", TermuxSetupCommands.STORAGE)
        copyCommand("Install the bundled helper after exporting it from onboarding",
            TermuxSetupCommands.INSTALL_HELPER)
        copyCommand("Pair Termux ADB (different identity)", TermuxSetupCommands.SETUP)
        copyCommand("Verify Termux ADB", TermuxSetupCommands.STATUS)
        copyCommand("Reconnect Termux ADB", TermuxSetupCommands.RECONNECT)
        body.addView(Button(context).apply {
            text="OPEN TERMUX"
            setOnClickListener {
                val intent=context.packageManager.getLaunchIntentForPackage("com.termux")
                if(intent!=null) context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else status.text="TERMUX NOT INSTALLED"
            }
        })
        body.addView(Button(context).apply {
            text="VERIFY TERMUX ADB · SEPARATE CHECK"
            setOnClickListener {
                isEnabled=false
                scope.launch {
                    try {
                        val result=Fr3kApplication.get().termuxBridge.runRaw(TermuxSetupCommands.STATUS, 8_000)
                        status.text=if(result.exitCode==0 &&
                            result.stdout.contains("CONNECTED") &&
                            result.stdout.contains("Android shell UID 2000"))
                            "TERMUX ADB VERIFIED · HUD pairing unchanged"
                        else "TERMUX ADB NOT VERIFIED · pair and connect via Termux; RUN_COMMAND permission may be required"
                    } finally { isEnabled=true }
                }
            }
        })
        body.addView(status)

        fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
        val frame=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(0xff151B30.toInt()) }
        val row=LinearLayout(context)
        val handle=label("WIRELESS DEBUG · move").apply { minHeight=dp(48) }
        row.addView(handle,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(Button(context).apply { text="×"; contentDescription="Close wireless panel"; setOnClickListener { hide() } },LinearLayout.LayoutParams(dp(48),dp(48)))
        frame.addView(row)
        frame.addView(ScrollView(context).apply { addView(body) },LinearLayout.LayoutParams(-1,0,1f))
        val grip=label("↘ Resize").apply { gravity=Gravity.END; minHeight=dp(48) };frame.addView(grip)
        val bounds=overlayHost.windowManager.maximumWindowMetrics.bounds
        val params=OverlayParams.forInput(dp(340).coerceAtMost(bounds.width()-dp(16)),dp(520).coerceAtMost(bounds.height()-dp(80))).apply { x=dp(8); y=dp(64) }
        root=frame;overlayHost.add(frame,params);FloatingWindowControls.install(overlayHost,frame,params,handle,grip)
    }
}
