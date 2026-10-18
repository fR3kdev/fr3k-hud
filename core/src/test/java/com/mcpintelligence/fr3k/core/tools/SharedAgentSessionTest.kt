package com.mcpintelligence.fr3k.core.tools

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedAgentSessionTest {
    private fun call(id: String, tool: String, args: Map<String,String>) = JSONObject().put("role", "assistant")
        .put("content", "I will run the requested operation.")
        .put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", SharedAgentSession.functionName(tool)).put("arguments", JSONObject(args).toString()))))
    private fun answer(text: String) = JSONObject().put("role", "assistant").put("content", text)
    private class Tool(override val id: String, override val capability: String) : AgentTool {
        override val displayName = id
        override val description = "test tool"
        override val requiresNetwork = false
        val contexts = mutableListOf<ToolContext>()
        override suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult {
            contexts.add(context)
            return ToolResult.Success("actual-result-7")
        }
    }

    @Test fun nativeCallWaitsForExactApprovalAndReturnsActualResult() = runTest {
        val tool = Tool("termux.exec", "termux")
        val bus = AgentToolBus().apply { register(tool) }
        var turn = 0
        val session = SharedAgentSession(bus, AgentSessionBackend { messages, _ ->
            if (turn++ == 0) call("call1", tool.id, mapOf("command" to "printf hello"))
            else {
                val last = messages.getJSONObject(messages.length()-1)
                assertEquals("tool", last.getString("role"))
                assertEquals("call1", last.getString("tool_call_id"))
                assertEquals("actual-result-7", last.getString("content"))
                answer("Tool returned actual-result-7.")
            }
        }, "phone")
        val job = launch { session.send("run printf") }
        runCurrent()
        val approval = session.approval.value!!
        assertEquals("printf hello", approval.arguments["command"])
        assertTrue(tool.contexts.isEmpty())
        session.decide("wrong-id", true)
        runCurrent()
        assertTrue(tool.contexts.isEmpty())
        session.decide(approval.id, true)
        job.join()
        assertEquals(1, tool.contexts.size)
        assertEquals(session.sessionId, tool.contexts.single().sessionId)
        assertTrue(session.lines.value.any { it.text == "actual-result-7" })
        assertFalse(session.busy.value)
    }

    @Test fun decliningNeverInvokesTool() = runTest {
        val tool = Tool("termux.exec", "termux")
        val bus = AgentToolBus().apply { register(tool) }
        var turn = 0
        val session = SharedAgentSession(bus, AgentSessionBackend { messages, _ ->
            if (turn++ == 0) call("call1", tool.id, mapOf("command" to "anything"))
            else { assertTrue(messages.toString().contains("tool.declined")); answer("Declined.") }
        }, "phone")
        val job = launch { session.send("request") }
        runCurrent()
        session.decide(session.approval.value!!.id, false)
        job.join()
        assertTrue(tool.contexts.isEmpty())
    }

    @Test fun cancellationDoesNotReplayPendingCallAndNextTurnKeepsHistory() = runTest {
        val tool = Tool("termux.exec", "termux")
        val bus = AgentToolBus().apply { register(tool) }
        var turn = 0
        val session = SharedAgentSession(bus, AgentSessionBackend { messages, _ ->
            if (turn++ == 0) call("cancelled-call", tool.id, mapOf("command" to "anything"))
            else {
                assertTrue(messages.toString().contains("Turn interrupted"))
                assertTrue(messages.toString().contains("first request"))
                answer("Second turn retained context.")
            }
        }, "phone")
        val job = launch { session.send("first request") }
        runCurrent()
        job.cancelAndJoin()
        assertNull(session.approval.value)
        assertFalse(session.busy.value)
        session.send("second request")
        assertTrue(tool.contexts.isEmpty())
    }
    @Test fun allowAllIsTaskScopedAndTheFollowingTurnNeedsApprovalAgain() = runTest {
        val tool = Tool("termux.exec", "termux")
        val bus = AgentToolBus().apply { register(tool) }
        var callIndex = 0
        val backend = AgentSessionBackend { _, _ ->
            when (callIndex++) {
                0 -> call("a1", tool.id, mapOf("command" to "echo first"))
                1 -> call("a2", tool.id, mapOf("command" to "echo second"))
                2 -> answer("First task finished.")
                3 -> call("b1", tool.id, mapOf("command" to "echo next"))
                else -> answer("Second task done.")
            }
        }
        val session = SharedAgentSession(bus, backend, "phone")
        val first = launch { session.send("task one") }
        runCurrent()
        val pending = session.approval.value!!
        session.decide(pending.id, true, allowAllThisTask = true)
        first.join()
        assertEquals(2, tool.contexts.size)
        assertNull(session.approval.value)
        val second = launch { session.send("task two") }
        runCurrent()
        assertNotNull("A new user turn MUST ask again", session.approval.value)
        assertEquals("echo next", session.approval.value!!.arguments["command"])
        assertEquals(2, tool.contexts.size)
        session.decide(session.approval.value!!.id, false)
        second.join()
        assertEquals(2, tool.contexts.size)
    }

    @Test fun duplicateCallsStopDispatchingAndToolRoundsRemainBounded() = runTest {
        val tool = Tool("blackwave.status", "blackwave")
        val session = SharedAgentSession(AgentToolBus().apply { register(tool) },
            AgentSessionBackend { _, _ ->
                call("same", tool.id, mapOf("action" to "status"))
            }, "phone", maxToolRounds = 24)
        session.send("check repeated status")
        assertEquals("No more than three identical tool dispatches", 3, tool.contexts.size)
        assertTrue(session.lines.value.any { it.text.contains("bounded tool-round budget") })
        assertFalse(session.busy.value)
    }

}
