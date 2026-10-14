package com.mcpintelligence.fr3k.capability

import org.json.JSONArray
import org.json.JSONObject

/** Closed step grammar. There is deliberately no free-form shell or code step. */
sealed class Step {
    data class Launch(val target: String) : Step()
    data class WaitFor(val selector: SelectorFingerprint, val timeoutMs: Int = 4000) : Step()
    data class Click(val selector: SelectorFingerprint) : Step()
    data class SetText(val selector: SelectorFingerprint, val text: String) : Step()
    data class AssertPresent(val selector: SelectorFingerprint) : Step()
    data class AssertAbsent(val selector: SelectorFingerprint) : Step()
    data class Mask(val selector: SelectorFingerprint) : Step()
    data class Unmask(val selector: SelectorFingerprint) : Step()
    data class Observe(val durationMs: Int) : Step()
    data class Broadcast(val action: String, val extras: Map<String, String> = emptyMap()) : Step()
    data class PrivilegedOp(val packageName: String, val op: String, val mode: String) : Step()

    fun toJson(): JSONObject = when (this) {
        is Launch -> JSONObject().put("op", "launch").put("target", target)
        is WaitFor -> JSONObject().put("op", "wait_for").put("selector", selector.toJson()).put("timeoutMs", timeoutMs)
        is Click -> JSONObject().put("op", "click").put("selector", selector.toJson())
        is SetText -> JSONObject().put("op", "set_text").put("selector", selector.toJson()).put("text", text)
        is AssertPresent -> JSONObject().put("op", "assert_present").put("selector", selector.toJson())
        is AssertAbsent -> JSONObject().put("op", "assert_absent").put("selector", selector.toJson())
        is Mask -> JSONObject().put("op", "mask").put("selector", selector.toJson())
        is Unmask -> JSONObject().put("op", "unmask").put("selector", selector.toJson())
        is Observe -> JSONObject().put("op", "observe").put("durationMs", durationMs)
        is Broadcast -> JSONObject().put("op", "broadcast").put("action", action).put("extras", JSONObject(extras as Map<*, *>))
        is PrivilegedOp -> JSONObject().put("op", "privileged").put("packageName", packageName).put("opName", op).put("mode", mode)
    }

    companion object {
        /** The complete grammar. A test pins this list so the surface cannot grow silently. */
        val ALLOWED_OPS = listOf(
            "launch", "wait_for", "click", "set_text", "assert_present", "assert_absent",
            "mask", "unmask", "observe", "broadcast", "privileged",
        )

        fun fromJson(json: JSONObject): Step = when (val op = json.getString("op")) {
            "launch" -> Launch(json.getString("target"))
            "wait_for" -> WaitFor(SelectorFingerprint.fromJson(json.getJSONObject("selector")), json.optInt("timeoutMs", 4000))
            "click" -> Click(SelectorFingerprint.fromJson(json.getJSONObject("selector")))
            "set_text" -> SetText(SelectorFingerprint.fromJson(json.getJSONObject("selector")), json.getString("text"))
            "assert_present" -> AssertPresent(SelectorFingerprint.fromJson(json.getJSONObject("selector")))
            "assert_absent" -> AssertAbsent(SelectorFingerprint.fromJson(json.getJSONObject("selector")))
            "mask" -> Mask(SelectorFingerprint.fromJson(json.getJSONObject("selector")))
            "unmask" -> Unmask(SelectorFingerprint.fromJson(json.getJSONObject("selector")))
            "observe" -> Observe(json.optInt("durationMs", 1000))
            "broadcast" -> Broadcast(json.getString("action"), json.optJSONObject("extras")?.toStringMap() ?: emptyMap())
            "privileged" -> PrivilegedOp(json.getString("packageName"), json.getString("opName"), json.getString("mode"))
            else -> throw IllegalArgumentException(
                "unsupported step op '$op'; allowed: ${ALLOWED_OPS.joinToString(", ")}"
            )
        }

        fun listToJson(steps: List<Step>): JSONArray = JSONArray().also { arr -> steps.forEach { arr.put(it.toJson()) } }

        fun listFromJson(arr: JSONArray): List<Step> = (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }
}

internal fun JSONObject.toStringMap(): Map<String, String> =
    keys().asSequence().associateWith { getString(it) }
