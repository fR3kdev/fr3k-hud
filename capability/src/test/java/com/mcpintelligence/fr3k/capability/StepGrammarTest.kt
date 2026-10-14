package com.mcpintelligence.fr3k.capability

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class StepGrammarTest {
    private val sel = SelectorFingerprint(className = "android.widget.Button", textRegex = "(?i)ok")

    private val everyStep = listOf(
        Step.Launch("fr3ktwo://open"),
        Step.WaitFor(sel, 1500),
        Step.Click(sel),
        Step.SetText(sel, "hello"),
        Step.AssertPresent(sel),
        Step.AssertAbsent(sel),
        Step.Mask(sel),
        Step.Unmask(sel),
        Step.Observe(250),
        Step.Broadcast("com.mcpintelligence.fr3k.hud.SOMETHING", mapOf("k" to "v")),
        Step.PrivilegedOp("com.x.y", "WAKE_LOCK", "deny"),
    )

    @Test fun everyStepRoundTripsThroughJson() {
        val json = Step.listToJson(everyStep)
        val back = Step.listFromJson(json)
        assertEquals(everyStep.size, back.size)
        back.forEachIndexed { index, step -> assertEquals(everyStep[index].toJson().toString(), step.toJson().toString()) }
    }

    @Test fun grammarCoversEveryStepAndForbidsCodeExecution() {
        assertEquals(everyStep.size, Step.ALLOWED_OPS.size)
        everyStep.forEach { assertTrue(Step.ALLOWED_OPS.contains(it.toJson().getString("op"))) }
        val banned = listOf("shell", "exec", "script", "eval", "dex", "cmd")
        assertTrue(Step.ALLOWED_OPS.none { op -> banned.any { op.contains(it) } })
    }

    @Test fun unknownOpIsRefusedWithAllowListMessage() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            Step.fromJson(org.json.JSONObject().put("op", "shell").put("command", "rm -rf /"))
        }
        assertTrue(error.message!!.contains("allowed:"))
    }

    @Test fun broadcastExtrasSurviveRoundTrip() {
        val arr = JSONArray().put(Step.Broadcast("a.b", mapOf("x" to "1", "y" to "2")).toJson())
        val back = Step.listFromJson(arr).single() as Step.Broadcast
        assertEquals("1", back.extras["x"])
        assertEquals("2", back.extras["y"])
    }
}
