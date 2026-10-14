package com.mcpintelligence.fr3k.ui.appcontrol

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.mcpintelligence.fr3k.appcontrol.*
import com.mcpintelligence.fr3k.ui.FloatingPanel
import kotlinx.coroutines.*
import java.io.File

/** User-supplied modules, staged installed APKs, reviewed installation and a verified original. */
class SelectedAppPatchActivity : ComponentActivity() {
    private lateinit var output: TextView
    private var module: File? = null
    private var prepared: PatchResult? = null
    private var panel: FloatingPanel? = null
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                module = withContext(Dispatchers.IO) {
                    val destination = File(cacheDir, "selected-hook.apk")
                    contentResolver.openInputStream(uri)!!.use { input -> destination.outputStream().use { out ->
                        val buffer=ByteArray(65536); var count=0L
                        while(true) { val n=input.read(buffer); if(n<0) break; count+=n; require(count<=PatchContract.MAX_BYTES) { "Module exceeds 512 MiB" }; out.write(buffer,0,n) }
                    } }
                    check(com.android.apksig.ApkVerifier.Builder(destination).build().verify().isVerified) { "Module APK signature is invalid" }
                    java.util.zip.ZipFile(destination).use { require(it.getEntry("assets/xposed_init")!=null) { "Choose an Xposed module APK (assets/xposed_init missing)" } }
                    destination
                }
                prepared=null; output.text="Module selected: ${module!!.name}. Preparing will modify only a staged copy."
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { module=null; prepared=null; output.text="Module rejected: ${e.message}" }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,48,24,48); setBackgroundColor(0xff080B18.toInt()) }
        fun label(text:String)=TextView(this).apply { this.text=text; textSize=14f; setTextColor(0xffE6ECF5.toInt()); setPadding(0,12,0,12) }
        body.addView(label("PATCH SELECTED APP\n1. Enter an installed package.\n2. Import an Xposed-compatible module APK designed for that app/build.\n3. Prepare a private copy, then review its signer, hash and warnings.\n4. Android's installer asks you to approve. HUD never uninstalls an app automatically.\n5. Original APK is archived before patching; review it below to restore.\n\nRe-signing changes identity and can break sign-in or integrity checks. Replacing either version may require uninstalling first, which loses app data. Split APKs need a split-aware patch recipe and are refused here. Importing a module grants that module code execution INSIDE the target app. A module's compatibility is its author's responsibility; test the resulting app before relying on it."))
        val pkg=EditText(this).apply { hint="Installed package name"; contentDescription="patch-target-package"; setText(intent.getStringExtra("package") ?: ""); setSingleLine(); setTextColor(0xffE6ECF5.toInt()); setHintTextColor(0xffB0BCD5.toInt()) };body.addView(pkg)
        output=label("No output prepared.").apply { setTextIsSelectable(true) }
        fun button(title:String, action:()->Unit) { body.addView(Button(this).apply { text=title; setOnClickListener { action() } }) }
        button("IMPORT COMPATIBLE MODULE APK") { pick.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }
        button("PREPARE SELECTED APP COPY") {
            val target=pkg.text.toString().trim(); val selected=module
            if(selected==null) { output.text="Import a compatible module first." } else lifecycleScope.launch {
                prepared=null; output.text="Staging, archiving and patching…"
                try {
                    val result=withContext(Dispatchers.IO) {
                        val work=File(noBackupFilesDir,"app-control/user-jobs/${java.util.UUID.randomUUID()}").apply { mkdirs() }
                        val staged=TargetResolver(this@SelectedAppPatchActivity).stage(target,work)
                        require(staged.splits.isEmpty()) { "Split target: no compatible split-aware recipe selected" }
                        LspatchPatchEngine(this@SelectedAppPatchActivity).patch(PatchRequest(staged.identity,staged.baseApk,selected,"User-selected module"),work)
                    }
                    prepared=result
                    output.text="PREPARED, NOT INSTALLED\nPackage: ${result.sheet.packageName}\nSHA256: ${result.sha256}\nFingerprint: ${result.sheet.appFingerprint}\nOriginal: ${result.sheet.archiveSha256}\n${result.sheet.warnings.joinToString("\n")}"
                } catch(e:CancellationException) { throw e }
                catch(e:Exception) { output.text="Preparation failed: ${e.message}. No installed app replaced." }
            }
        }
        button("REVIEW PREPARED INSTALLATION") {
            val candidate=prepared
            if(candidate==null) output.text="Prepare the selected app first." else {
                panel?.dismiss()
                panel=FloatingPanel.review(this,"Install ${candidate.sheet.packageName}?",
                    "Output SHA256: ${candidate.sha256}\n${candidate.sheet.warnings.joinToString("\n")}\nNo uninstall is performed automatically. Compatibility must be checked after installation.","ACKNOWLEDGE & OPEN INSTALLER") { install(candidate.outputApk) }
            }
        }
        button("REVIEW ARCHIVED ORIGINAL (UNDO)") {
            val candidate=prepared
            if(candidate==null) output.text="Prepare the selected app first to create its original archive." else {
                val original=File(candidate.sheet.archivePath)
                if(!original.isFile || ApkHashing.sha256(original)!=candidate.sheet.archiveSha256) output.text="Original archive failed hash verification." else {
                    panel?.dismiss()
                    panel=FloatingPanel.review(this,"Restore original?","Original SHA256: ${candidate.sheet.archiveSha256}\nSwitching signing identities may require uninstalling the patched build and lose its private data. HUD never uninstalls it automatically.","OPEN ORIGINAL INSTALLER") {
                        val shared=File(filesDir,"shares/original-${candidate.sheet.packageName}.apk").apply { parentFile!!.mkdirs() }; original.copyTo(shared,true); install(shared)
                    }
                }
            }
        }
        body.addView(output)
        button("CLOSE") { finish() }
        setContentView(ScrollView(this).apply { addView(body); setOnApplyWindowInsetsListener { view,insets -> val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets } })
    }
    private fun install(file:File) {
        if(!packageManager.canRequestPackageInstalls()) { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,android.net.Uri.parse("package:$packageName"))); output.text="Allow installation from HUD, then review again."; return }
        val uri=androidx.core.content.FileProvider.getUriForFile(this,"$packageName.fileprovider",file)
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    override fun onDestroy() { panel?.dismiss(); super.onDestroy() }
}
