package com.mcpintelligence.fr3k

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcpintelligence.fr3k.core.tools.AgentSessionBackend
import com.mcpintelligence.fr3k.core.tools.AgentToolBus
import com.mcpintelligence.fr3k.core.tools.SharedAgentSession
import com.mcpintelligence.fr3k.hud.overlays.Fr3kChatBubble
import com.mcpintelligence.fr3k.hud.overlays.OverlayHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reproduces the user-visible six-line cut-off and verifies a second send is not blocked. */
@RunWith(AndroidJUnit4::class)
class ChatTranscriptRegressionDeviceTest {
    @Test fun longAnswerRemainsVisibleAndSubsequentTurnWorks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Fr3kApplication>()
        val longAnswer = (1..20).joinToString("\n") { "full-chat-reply-line-$it" }
        var calls = 0
        val session = SharedAgentSession(AgentToolBus(), AgentSessionBackend { _, _ ->
            JSONObject().put("role", "assistant").put(
                "content", if (++calls == 1) longAnswer else "SECOND_ANSWER_CONFIRMED"
            )
        }, "chat-regression")
        val chat = withContext(Dispatchers.Main) {
            Fr3kChatBubble(OverlayHost(context, context.getSystemService(Context.WINDOW_SERVICE) as WindowManager), session = session)
        }
        fun viewTree(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { viewTree(view.getChildAt(it)) } else emptyList()
        try {
            session.send("first")
            withTimeout(5000) {
                while (viewTree(chat.rootView()).filterIsInstance<TextView>().none {
                        it.text.toString().contains("full-chat-reply-line-20")
                    }) delay(30)
            }
            val transcript = viewTree(chat.rootView()).filterIsInstance<ScrollView>().firstNotNullOfOrNull {
                it.getChildAt(0) as? TextView
            } ?: error("Chat transcript ScrollView absent")
            assertTrue("Reply cut off after six lines", transcript.text.contains("full-chat-reply-line-20"))
            assertTrue("Transcript still enforces a short line limit", transcript.maxLines >= 20)
            assertFalse(session.busy.value)
            session.send("second")
            withTimeout(5000) {
                while (!transcript.text.contains("SECOND_ANSWER_CONFIRMED")) delay(30)
            }
            assertEquals(2, calls)
            assertFalse(session.busy.value)
        } finally {
            session.cancel()
            withContext(Dispatchers.Main) { chat.shutdown() }
        }
    }
}
