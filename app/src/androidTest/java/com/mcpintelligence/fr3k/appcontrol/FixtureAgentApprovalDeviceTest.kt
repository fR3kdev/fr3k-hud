package com.mcpintelligence.fr3k.appcontrol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.core.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Controlled backend fixture, real approval gate/tool/runtime. No live model inference claimed. */
@RunWith(AndroidJUnit4::class)
class FixtureAgentApprovalDeviceTest {
    @Test fun rejectDoesNotDispatchAndApproveRunsTheRealOwnedHook() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = OwnedFixtureController(context)
        controller.change(true)
        val delegated = OwnedFixtureAgentTool(controller)
        val invocations = AtomicInteger()
        val wrapped = object : AgentTool by delegated {
            override suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult {
                invocations.incrementAndGet()
                return delegated.invoke(context, args)
            }
        }
        suspend fun execute(allow: Boolean): SharedAgentSession {
            val rounds = AtomicInteger()
            val backend = AgentSessionBackend { _, _ ->
                if (rounds.getAndIncrement() == 0) JSONObject()
                    .put("role", "assistant").put("content", "Review owned fixture change")
                    .put("tool_calls", JSONArray().put(JSONObject().put("id", "owned-fixture-call")
                        .put("type", "function").put("function", JSONObject()
                            .put("name", SharedAgentSession.functionName(delegated.id))
                            .put("arguments", JSONObject().put("visible", "false").toString()))))
                else JSONObject().put("role", "assistant").put("content", "Controlled test complete")
            }
            val bus = AgentToolBus().apply { register(wrapped) }
            val session = SharedAgentSession(bus, backend, "owned-emulator")
            val job = async(Dispatchers.Default) { session.send("Hide only the owned test fixture banner") }
            val request = withTimeout(5000) { session.approval.first { it != null } }!!
            assertEquals(delegated.id, request.toolId)
            session.decide(request.id, allow)
            withTimeout(10000) { job.await() }
            return session
        }
        val rejected = execute(false)
        assertEquals(0, invocations.get())
        assertTrue(rejected.lines.value.any { it.text.contains("tool.declined") })
        val accepted = execute(true)
        assertEquals(1, invocations.get())
        assertTrue(accepted.lines.value.any { it.text.contains("bannerVisible=false") && it.text.contains("pid=") })
        controller.change(true)
    }
}
