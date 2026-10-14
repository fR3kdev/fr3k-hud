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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xff0a0a14.toInt())
        }
        body.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
        fun text(value: String, size: Float = 14f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(0xffe5e7eb.toInt())
            typeface = Typeface.MONOSPACE; setPadding(0, pad / 2, 0, pad / 2)
        }
        body.addView(text("fR3k // APP CONTROL", 22f).apply { setTextColor(0xffe879f9.toInt()) })
        body.addView(text("INSPECTION AVAILABLE\nPATCH ENGINE: NOT QUALIFIED\nLIVE HOOKS: NOT AVAILABLE"))
        val status = text("")
        fun refreshStatus() {
            status.text = if (HudPrivilegedBridge.serviceOrNull() != null)
                "HUD shell service: BINDER CONNECTED (read-only)" else
                "HUD shell service: DISCONNECTED — authorised ADB bootstrap required"
        }
        refreshStatus(); body.addView(status)
        val target = EditText(this).apply {
            hint = "Installed package name"
            setText("com.mcpintelligence.fr3k.patchfixture")
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
            text = "SHOW AUTHORISED ADB BOOTSTRAP COMMAND"
            setOnClickListener {
                output.text = "Run only from your authorised ADB host. No Shizuku or LSPatch manager is needed for this read-only service. Wireless pairing is not integrated yet.\n\nadb shell 'CLASSPATH=\"${applicationInfo.sourceDir}\" app_process /system/bin com.mcpintelligence.fr3k.privileged.HudPrivilegedMain'\n\nKeep the session open. Service expires after 30 minutes or HUD process death."
            }
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
        body.addView(Button(this).apply { text = "CLOSE"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(body) })
        body.requestApplyInsets()
    }
}
