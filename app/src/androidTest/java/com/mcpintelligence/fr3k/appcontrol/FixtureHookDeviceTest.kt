package com.mcpintelligence.fr3k.appcontrol

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Run only after preparing the owned fixture with the qualified embedded loader/module. */
@RunWith(AndroidJUnit4::class)
class FixtureHookDeviceTest {
    @Test fun ownedMethodChangesTrueFalseTrueInOneProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launch = context.packageManager.getLaunchIntentForPackage("com.mcpintelligence.fr3k.patchfixture")
            ?: error("Install the owned prepared fixture first")
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun change(value: Boolean): Bundle {
            val id = UUID.randomUUID().toString()
            val latch = CountDownLatch(1)
            val receipt = AtomicReference<Bundle?>()
            val intent = Intent("com.mcpintelligence.fr3k.hud.SET_OWNED_FIXTURE_BANNER")
                .setPackage("com.mcpintelligence.fr3k.patchfixture")
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("visible", value).putExtra("operationId", id)
            context.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (resultCode == Activity.RESULT_OK && resultData == "OWNED_METHOD_OBSERVED")
                        receipt.set(getResultExtras(false))
                    latch.countDown()
                }
            }, Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null)
            assertTrue("Timed out waiting for owned runtime", latch.await(5, TimeUnit.SECONDS))
            val result = receipt.get() ?: error("No authenticated owned-runtime acknowledgement")
            assertEquals(id, result.getString("operationId"))
            assertEquals(value, result.getBoolean("observed"))
            return result
        }
        // ART loader startup can take seconds; do not confuse that with a ready runtime.
        var firstReceipt: Bundle? = null
        val readyDeadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (firstReceipt == null && android.os.SystemClock.elapsedRealtime() < readyDeadline) {
            try { firstReceipt = change(true) }
            catch (_: IllegalStateException) { Thread.sleep(100) }
        }
        val initial = firstReceipt ?: error("Owned hook runtime never became ready")
        val hidden = change(false)
        val restored = change(true)
        assertTrue(initial.getInt("pid") > 0)
        assertEquals(initial.getInt("pid"), hidden.getInt("pid"))
        assertEquals(initial.getInt("pid"), restored.getInt("pid"))
        android.util.Log.i("FR3KHK", "Verified true/false/true owned method PID=${initial.getInt("pid")}")
    }
}
