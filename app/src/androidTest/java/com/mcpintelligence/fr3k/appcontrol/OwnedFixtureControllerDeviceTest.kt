package com.mcpintelligence.fr3k.appcontrol

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OwnedFixtureControllerDeviceTest {
    @Test fun exactOnDeviceSignedFixtureIsControlledAndRestored() = runBlocking<Unit> {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(OwnedFixtureController.TARGET)
            ?: error("Install the on-device prepared fixture")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val controller = OwnedFixtureController(context)
        var ready: String? = null
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (ready == null && android.os.SystemClock.elapsedRealtime() < deadline) {
            try { ready = controller.change(true) }
            catch (_: IllegalStateException) { Thread.sleep(100) }
        }
        checkNotNull(ready) { "Exact prepared fixture not ready" }
        fun capture(name: String) {
            Thread.sleep(200) // Allow the target's next rendered frames after its acknowledged update.
            val bitmap = instrument.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        val hidden = controller.change(false)
        assertTrue(hidden.contains("bannerVisible=false"))
        capture("owned-banner-hidden.png")
        val restored = controller.change(true)
        assertTrue(restored.contains("bannerVisible=true"))
        capture("owned-banner-restored.png")
        val pid = Regex("pid=(\\d+)")
        assertEquals(pid.find(ready)!!.groupValues[1], pid.find(hidden)!!.groupValues[1])
        assertEquals(pid.find(hidden)!!.groupValues[1], pid.find(restored)!!.groupValues[1])
        android.util.Log.i("FR3KHK", "ON_DEVICE_SIGNED_CONTROLLER_PASS $restored")
    }
}
