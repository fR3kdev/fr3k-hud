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
    private var preparedForInstall: com.mcpintelligence.fr3k.appcontrol.EmbeddedFixturePatchEngine.Prepared? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xff0a0a14.toInt())
        }
        // Insets belong on the scrolling root so the last control clears the navigation gesture handle.
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        fun text(value: String, size: Float = 14f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(0xffe5e7eb.toInt())
            typeface = Typeface.MONOSPACE; setPadding(0, pad / 2, 0, pad / 2)
        }
        body.addView(text("fR3k // APP CONTROL", 22f).apply { setTextColor(0xffe879f9.toInt()) })
        body.addView(text("INSPECTION AVAILABLE\nPATCH / LIVE CONTROL: OWNED FIXTURE LAB ONLY\nTHIRD-PARTY RECIPES: NOT AVAILABLE"))
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
        body.addView(Button(this).apply {
            text = "LAB: PREPARE OWNED FIXTURE ON THIS DEVICE"
            setOnClickListener {
                isEnabled = false
                output.text = "Preparing only the bundled owned test fixture. No installed app will be replaced."
                lifecycleScope.launch {
                    try {
                        preparedForInstall = com.mcpintelligence.fr3k.appcontrol.EmbeddedFixturePatchEngine(this@AppControlActivity).prepare()
                        output.text = "Owned fixture prepared and signed with this installation's AndroidKeyStore key.\nSHA256: ${preparedForInstall!!.sha256}\nNot installed. Use the reviewed installation button."
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { output.text = "Preparation failed: ${e.message}" }
                    finally { isEnabled = true }
                }
            }
        })
        body.addView(Button(this).apply {
            text = "LAB: REVIEW OWNED FIXTURE INSTALLATION"
            setOnClickListener {
                val candidate = preparedForInstall
                if (candidate == null) { output.text = "Prepare the owned fixture first."; return@setOnClickListener }
                android.app.AlertDialog.Builder(this@AppControlActivity)
                    .setTitle("Install ONLY the owned test fixture?")
                    .setMessage("Target: com.mcpintelligence.fr3k.patchfixture. Re-signing may require removing the old fixture and loses that fixture's data. HUD never uninstalls it automatically. No third-party application is selected. Continue to Android's installer?")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("OPEN ANDROID INSTALLER") { _, _ ->
                        if (!packageManager.canRequestPackageInstalls()) {
                            startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName")))
                        } else {
                            val uri = androidx.core.content.FileProvider.getUriForFile(this@AppControlActivity,
                                "$packageName.fileprovider", candidate.apk)
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                                .setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    }.show()
            }
        })
        body.addView(Button(this).apply {
            text = "LAB: LAUNCH PREPARED OWNED FIXTURE"
            setOnClickListener {
                val intent = packageManager.getLaunchIntentForPackage(com.mcpintelligence.fr3k.appcontrol.OwnedFixtureController.TARGET)
                if (intent != null) startActivity(intent) else output.text = "Install the owned fixture first."
            }
        })
        for (show in listOf(false, true)) body.addView(Button(this).apply {
            text = if (show) "LAB: RESTORE OWNED FIXTURE BANNER (UNDO)" else "LAB: HIDE OWNED FIXTURE BANNER"
            setOnClickListener {
                isEnabled = false
                lifecycleScope.launch {
                    try { output.text = com.mcpintelligence.fr3k.appcontrol.OwnedFixtureController(this@AppControlActivity).change(show) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { output.text = "Operation not confirmed: ${e.message}" }
                    finally { isEnabled = true }
                }
            }
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
