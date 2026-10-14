package com.mcpintelligence.fr3k.ui.screenshot

import android.app.Activity
import android.content.Intent
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mcpintelligence.fr3k.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenshotActivity : ComponentActivity() {
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startForegroundService(Intent(this, ScreenCaptureService::class.java).putExtra("resultCode", result.resultCode).putExtra("consent", result.data))
        } else ScreenCaptureService.status.value = "Capture cancelled. Nothing was saved."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Fr3kTheme {
            val file by ScreenCaptureService.result.collectAsState()
            val status by ScreenCaptureService.status.collectAsState()
            val scope = rememberCoroutineScope()
            var message by remember { mutableStateOf("") }
            var saving by remember { mutableStateOf(false) }
            val bitmap by produceState<android.graphics.Bitmap?>(null, file) {
                value = withContext(Dispatchers.IO) { file?.let { BitmapFactory.decodeFile(it.absolutePath) } }
            }
            Column(Modifier.fillMaxSize().background(Fr3kPalette.Bg).windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Screenshot", style = MaterialTheme.typography.displaySmall)
                FeatureGuide("Screenshot")
                DeckText(status)
                Button(onClick = {
                    projectionLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                }) { Text("Capture screen") }
                bitmap?.let { Image(it.asImageBitmap(), "Actual captured screen", modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) }
                Button(onClick = {
                    val captured = file ?: return@Button
                    saving = true
                    scope.launch {
                        message = withContext(Dispatchers.IO) {
                            var uri: android.net.Uri? = null
                            runCatching {
                                uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                                    put(MediaStore.Images.Media.DISPLAY_NAME, captured.name)
                                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/fR3k")
                                    put(MediaStore.Images.Media.IS_PENDING, 1)
                                }) ?: error("Media store refused image")
                                contentResolver.openOutputStream(uri!!)?.use { out -> captured.inputStream().use { it.copyTo(out) } } ?: error("Could not open output")
                                contentResolver.update(uri!!, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                                "Saved to Pictures/fR3k. Delete from Photos to undo."
                            }.getOrElse { uri?.let { pending -> contentResolver.delete(pending, null, null) }; "Save failed: ${it.message}" }
                        }
                        saving = false
                    }
                }, enabled = file != null && !saving) { Text(if (saving) "Saving…" else "Save to Pictures") }
                OutlinedButton(onClick = {
                    val captured = file ?: return@OutlinedButton
                    val uri = FileProvider.getUriForFile(this@ScreenshotActivity, "$packageName.fileprovider", captured)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share captured image"))
                }, enabled = file != null) { Text("Share image") }
                OutlinedButton(onClick = {
                    file?.delete(); ScreenCaptureService.result.value = null; ScreenCaptureService.status.value = "Private capture deleted. Copies saved or shared remain with their recipients."
                }, enabled = file != null) { Text("Delete private capture") }
                DeckText(message)
                Fr3kPanel(title = "Image analysis · unavailable") {
                    DeckText("OCR, translation, research and developer-agent image analysis are not connected to an image pipeline in this build. Use Share to choose an app that supports them. HUD does not report analysis of a placeholder as success.")
                }
                OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        } }
    }
}
