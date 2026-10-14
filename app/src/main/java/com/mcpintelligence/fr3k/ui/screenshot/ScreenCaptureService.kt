package com.mcpintelligence.fr3k.ui.screenshot

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.mcpintelligence.fr3k.hud.R
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** One explicitly consented frame, then releases the display, projection and foreground service. */
class ScreenCaptureService : Service() {
    companion object {
        val result = MutableStateFlow<File?>(null)
        val status = MutableStateFlow("No capture yet. Tap Capture screen and approve Android's prompt.")
    }
    private var projection: MediaProjection? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var completed = false
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("capture", "Screen capture", NotificationManager.IMPORTANCE_LOW))
        startForeground(7003, NotificationCompat.Builder(this, "capture").setSmallIcon(R.drawable.ic_fr3k_notification)
            .setContentTitle("Capturing one screen frame").setContentText("Stops after capture or timeout").build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        result.value = null; status.value = "Capturing…"; completed = false
        val data = intent?.getParcelableExtra("consent", Intent::class.java)
        val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        if (data == null || code != Activity.RESULT_OK) { status.value = "Capture cancelled; no image saved."; stopSelf(); return START_NOT_STICKY }
        try {
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { if (!completed) status.value = "Screen sharing stopped before capture."; stopSelf() }
            }, handler)
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val width = bounds.width(); val height = bounds.height()
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            // A short delay gives the consent UI time to leave the screen.
            handler.postDelayed({
                if (completed || projection == null) return@postDelayed
                reader?.setOnImageAvailableListener({ source ->
                    val frame = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                    frame.use {
                        if (completed) return@use
                        completed = true
                        try {
                            val plane = it.planes[0]
                            val paddedWidth = plane.rowStride / plane.pixelStride
                            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                            padded.copyPixelsFromBuffer(plane.buffer)
                            val bitmap = Bitmap.createBitmap(padded, 0, 0, width, height)
                            val directory = File(filesDir, "captures").apply { mkdirs() }
                            val file = File(directory, "capture-${System.currentTimeMillis()}.png")
                            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                            if (bitmap !== padded) bitmap.recycle()
                            padded.recycle()
                            result.value = file
                            status.value = "Captured ${width}×${height}. Stored privately on this device."
                        } catch (error: Exception) { status.value = "Capture failed: ${error.message}" }
                        stopSelf()
                    }
                }, handler)
                display = projection?.createVirtualDisplay("fR3k one frame", width, height, resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
            }, 350)
            handler.postDelayed({ if (!completed) { completed = true; status.value = "Capture timed out; no image saved."; stopSelf() } }, 10000)
        } catch (error: Exception) { status.value = "Capture failed: ${error.message}"; stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        completed = true
        handler.removeCallbacksAndMessages(null)
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
