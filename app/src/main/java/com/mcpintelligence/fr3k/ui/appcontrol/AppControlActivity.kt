package com.mcpintelligence.fr3k.ui.appcontrol

import android.os.Bundle
import android.graphics.Typeface
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mcpintelligence.fr3k.appcontrol.AndroidPackageInspector
import com.mcpintelligence.fr3k.integrations.privileged.HudPrivilegedBridge
import kotlinx.coroutines.*

/** Explicit local inspection UI; no hook or preparation success is implied. */
class AppControlActivity : ComponentActivity() {
    private var reviewPanel: com.mcpintelligence.fr3k.ui.FloatingPanel? = null
    override fun onDestroy() { reviewPanel?.dismiss(); super.onDestroy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xff080B18.toInt())
        }
        // Insets belong on the scrolling root so the last control clears the navigation gesture handle.
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        fun text(value: String, size: Float = 14f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(0xffe5e7eb.toInt())
            typeface = Typeface.MONOSPACE; setPadding(0, pad / 2, 0, pad / 2)
        }
        body.addView(text("fR3k // APP CONTROL", 22f).apply { setTextColor(0xffe879f9.toInt()) })
        body.addView(text("INSPECTION AVAILABLE\nSELECT INSTALLED APPS FOR BRIDGE & SHELL CONTROL\nPATCHING REQUIRES A COMPATIBLE MODULE"))
        val status = text("")
        fun refreshStatus() {
            status.text = if (HudPrivilegedBridge.serviceOrNull() != null)
                "HUD shell service: BINDER CONNECTED" else
                "HUD shell service: DISCONNECTED — authorised ADB bootstrap required"
        }
        refreshStatus(); body.addView(status)
        val target = EditText(this).apply {
            hint = "Installed package name"
            setText("")
            setTextColor(0xffe5e7eb.toInt()); setHintTextColor(0xff9ca3af.toInt())
            setSingleLine(true)
            contentDescription = "app-control-target-package"
        }
        body.addView(target)
        val output = text("Choose one installed package. No application data will be read or modified.")
            .apply { setTextIsSelectable(true); contentDescription = "app-control-result" }
        body.addView(Button(this).apply {
            text = "INSPECT SELECTED APP — READ ONLY"
            setOnClickListener {
                val pkg = target.text.toString().trim()
                isEnabled = false
                output.text = "Inspecting selected APK files…"
                lifecycleScope.launch {
                    try {
                        val observed = withTimeout(30_000) { AndroidPackageInspector(this@AppControlActivity).inspect(pkg) }
                        val shell = withContext(Dispatchers.IO) {
                            HudPrivilegedBridge.serviceOrNull()?.inspectPackage(pkg)
                        }
                        output.text = buildString {
                            append("Package: ${observed.packageName}\nVersion: ${observed.versionCode}\n")
                            append("Signer SHA256: ${observed.signerSha256}\n")
                            observed.splitSha256.forEachIndexed { index, hash -> append("APK[$index]: $hash\n") }
                            append("Shell observation: ${shell?.getInt("serviceUid") ?: "not connected"}\n")
                            append("Recipe: UNSUPPORTED — no qualified recipe for this build\nApplied: false")
                        }
                    } catch (e: CancellationException) {
                        output.text = "Inspection cancelled or timed out. Nothing modified."
                        throw e
                    } catch (e: Exception) {
                        output.text = "Inspection failed: ${e.message ?: e.javaClass.simpleName}\nNothing modified."
                    } finally { isEnabled = true; refreshStatus() }
                }
            }
        })
        body.addView(Button(this).apply {
            text = "BROWSE INSTALLED APPS"
            setOnClickListener {
                val apps = packageManager.getInstalledApplications(0).sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }
                val list = LinearLayout(this@AppControlActivity).apply { orientation = LinearLayout.VERTICAL }
                apps.forEach { app -> list.addView(Button(this@AppControlActivity).apply {
                    text = "${packageManager.getApplicationLabel(app)}\n${app.packageName}"
                    setOnClickListener { target.setText(app.packageName); reviewPanel?.dismiss() }
                }) }
                reviewPanel?.dismiss()
                reviewPanel = com.mcpintelligence.fr3k.ui.FloatingPanel(this@AppControlActivity, "Choose installed app", ScrollView(this@AppControlActivity).apply { addView(list) }).show()
            }
        })
        for (enable in listOf(true, false)) body.addView(Button(this).apply {
            text = if (enable) "ENABLE SELECTED APP CONTROLS" else "REMOVE SELECTED APP CONTROLS"
            setOnClickListener {
                val pkg = target.text.toString().trim()
                isEnabled = false
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            com.mcpintelligence.fr3k.appcontrol.CapabilityCatalogue.select(this@AppControlActivity, pkg, enable)
                            com.mcpintelligence.fr3k.Fr3kApplication.get().capabilityHost.reset()
                        }
                        output.text = if (enable) "Selected $pkg. Open Command Deck → Workflows to review launch and eight app-op controls. APP MODS provides a guide for every action. App changes require review; Android permissions still apply." else "Removed $pkg from the catalogue. Existing app-op modes are unchanged; restore them before removing controls."
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { output.text = "Selection failed: ${e.message}" }
                    finally { isEnabled = true }
                }
            }
        })
        body.addView(Button(this).apply {
            text = "MAP SELECTED APP HOME — READ SCREEN CONTROLS"
            setOnClickListener {
                val pkg=target.text.toString().trim(); isEnabled=false
                lifecycleScope.launch {
                    try {
                        val count=withContext(Dispatchers.IO) {
                            val service=com.mcpintelligence.fr3k.bridge.Fr3kBridgeService.require()
                            require(com.mcpintelligence.fr3k.bridge.BridgeGuard.mayActOn(pkg,setOf(pkg))) { "Android system and HUD surfaces are not automation targets" }
                            com.mcpintelligence.fr3k.appcontrol.CapabilityCatalogue.select(this@AppControlActivity,pkg,true)
                            val identity=com.mcpintelligence.fr3k.appcontrol.AndroidIdentityReader.of(this@AppControlActivity,pkg)
                            val result=com.mcpintelligence.fr3k.bridge.AppMapExplorer(service).explore(identity,listOf("home" to pkg),0)
                            com.mcpintelligence.fr3k.appcontrol.UserAppMaps.save(this@AppControlActivity,result.map)
                            com.mcpintelligence.fr3k.Fr3kApplication.get().capabilityHost.reset()
                            com.mcpintelligence.fr3k.appcontrol.UserAppMaps.descriptors(this@AppControlActivity,mapOf(pkg to identity)).size
                        }
                        output.text="Recorded $count fingerprinted screen controls. Return to HUD → Workflows to review each tap. No control was pressed during mapping. Password fields, destructive labels and ambiguous matches are excluded. Custom canvas controls may expose no accessibility nodes. APK changes invalidate this map. Screen actions may have no automatic inverse."
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) { output.text="Mapping failed: ${e.message}" }
                    finally { isEnabled=true }
                }
            }
        })
        body.addView(Button(this).apply {
            text = "SHOW AUTHORISED ADB BOOTSTRAP COMMAND"
            setOnClickListener {
                output.text = "Run only from your authorised ADB host. HUD owns this shell service; no companion manager is required. Or use WIRELESS DEBUG below to pair and start entirely on this phone.\n\nadb shell 'CLASSPATH=\"${applicationInfo.sourceDir}\" app_process /system/bin com.mcpintelligence.fr3k.privileged.HudPrivilegedMain'\n\nKeep the session open. Service expires after 30 minutes or HUD process death."
            }
        })
        body.addView(Button(this).apply {
            text = "WIRELESS DEBUG — PAIR & START WITHOUT A COMPUTER"
            setOnClickListener { startActivity(android.content.Intent(this@AppControlActivity, com.mcpintelligence.fr3k.ui.integrations.WirelessDebuggingActivity::class.java)) }
        })
        body.addView(Button(this).apply {
            text = "STOP HUD PRIVILEGED SERVICE"
            setOnClickListener {
                lifecycleScope.launch {
                    try { withContext(Dispatchers.IO) { HudPrivilegedBridge.serviceOrNull()?.shutdown() } }
                    catch (_: android.os.RemoteException) { }
                    output.text = "Stop requested; no app modification performed."
                    refreshStatus()
                }
            }
        })
        body.addView(output)
        body.addView(Button(this).apply {
            text = "PATCH SELECTED APP — IMPORT MODULE & REVIEW"
            setOnClickListener { startActivity(android.content.Intent(this@AppControlActivity, SelectedAppPatchActivity::class.java).putExtra("package",target.text.toString().trim())) }
        })
        body.addView(Button(this).apply { text = "CLOSE"; setOnClickListener { finish() } })
        scroll.addView(body)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            body.setPadding(pad + bars.left, pad, pad + bars.right, pad)
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }
}
