package com.mcpintelligence.fr3k.appcontrol

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Button
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.tools.*
import com.mcpintelligence.fr3k.ui.appcontrol.AppControlActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AppInspectionDeviceTest {
    @Test fun actualFixtureIdentityIsAvailableOnSharedToolBus() = runBlocking {
        val app = Fr3kApplication.get()
        app.agentSession // Initialise registrations, without sending any model request.
        val result = app.agentToolBus.dispatch(ToolContext("device-test", deviceId = "emulator"),
            "app.control.inspect", ToolArgs(mapOf("package" to "com.mcpintelligence.fr3k.patchfixture")))
        assertTrue(result is ToolResult.Success)
        val fields = (result as ToolResult.Success).structured
        assertEquals("1", fields["versionCode"])
        assertEquals("false", fields["applied"])
        assertTrue(fields.getValue("signerSha256").matches(Regex("[0-9a-f]{64}")))
        assertTrue(fields.getValue("splitSha256").matches(Regex("[0-9a-f]{64}")))
        val plan = app.agentToolBus.dispatch(ToolContext("s", deviceId = "d"), "app.control.plan",
            ToolArgs(mapOf("package" to "com.google.android.youtube"))) as ToolResult.Success
        assertEquals("UNSUPPORTED", plan.structured["mode"])
    }
    @Test fun localScreenInspectsFixtureWithoutClaimingHooks() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, AppControlActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AppControlActivity
        fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
            (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
        val result = AtomicReference("")
        try {
            instrument.runOnMainSync {
                views(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.text.toString().startsWith("INSPECT SELECTED APP") }.performClick()
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            do {
                instrument.runOnMainSync {
                    result.set(views(activity.window.decorView).filterIsInstance<TextView>()
                        .single { it.contentDescription == "app-control-result" }.text.toString())
                }
                if (result.get().contains("Applied: false")) break
                Thread.sleep(50)
            } while (android.os.SystemClock.elapsedRealtime() < deadline)
            assertTrue(result.get(), result.get().contains("Package: com.mcpintelligence.fr3k.patchfixture"))
            assertTrue(result.get().contains("Recipe: UNSUPPORTED"))
            fun save(name: String) {
                val bitmap = instrument.uiAutomation.takeScreenshot()
                assertNotNull(bitmap)
                File(instrument.targetContext.getExternalFilesDir(null), name).outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            save("app-control.png")
            val scroll = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as android.widget.ScrollView
            instrument.runOnMainSync { scroll.fullScroll(View.FOCUS_DOWN) }
            Thread.sleep(300)
            val close = views(activity.window.decorView).filterIsInstance<Button>()
                .single { it.text.toString().trim() == "CLOSE" }
            val buttonRect = android.graphics.Rect()
            val windowRect = android.graphics.Rect()
            instrument.runOnMainSync {
                close.getGlobalVisibleRect(buttonRect)
                activity.window.decorView.getWindowVisibleDisplayFrame(windowRect)
            }
            assertTrue("Last control clipped by system bars: $buttonRect within $windowRect",
                buttonRect.height() > 0 && buttonRect.bottom <= windowRect.bottom && buttonRect.top >= windowRect.top)
            save("app-control-bottom.png")
        } finally { instrument.runOnMainSync { activity.finish() } }
    }
}
