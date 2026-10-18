package com.mcpintelligence.fr3k.hud.overlays

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.mcpintelligence.fr3k.core.tools.SharedAgentSession
import kotlinx.coroutines.*

/** Same pending action and conversation on every chat surface. */
class AgentSessionPanel(context: Context, private val session: SharedAgentSession) : LinearLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val details = TextView(context).apply { setTextColor(0xffe8eaf2.toInt()); textSize = 14f }
    private val actions = LinearLayout(context).apply { orientation = VERTICAL }
    init {
        orientation = VERTICAL
        val review = android.widget.ScrollView(context).apply { addView(details) }
        addView(review, LayoutParams(LayoutParams.MATCH_PARENT, 96.dp()))
        val singleActions = LinearLayout(context).apply { orientation = HORIZONTAL }
        singleActions.addView(Button(context).apply {
            text = "ALLOW"
            setOnClickListener { session.approval.value?.let { session.decide(it.id, true) } }
        }, LayoutParams(0, 48.dp(), 1f))
        singleActions.addView(Button(context).apply {
            text = "DECLINE"
            setOnClickListener { session.approval.value?.let { session.decide(it.id, false) } }
        }, LayoutParams(0, 48.dp(), 1f))
        actions.addView(singleActions)
        actions.addView(Button(context).apply {
            text = "ALLOW ALL · THIS TASK"
            contentDescription = "Approve up to 24 registered tool calls for current task only; 10 minute limit"
            setOnClickListener { session.approval.value?.let { session.decide(it.id, true, allowAllThisTask = true) } }
        }, LayoutParams(LayoutParams.MATCH_PARENT, 48.dp()))
        addView(actions)
        addView(Button(context).apply {
            text = "STOP AGENT"
            setOnClickListener { session.cancel() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, 48.dp()))
        scope.launch { session.approval.collect { request ->
            review.visibility = if (request == null) View.GONE else View.VISIBLE
            actions.visibility = review.visibility
            details.text = request?.let { "Review ${it.toolId}\n" +
                it.arguments.entries.joinToString("\n") { (k,v) -> "$k: $v" } +
                "\nALLOW ALL: this task only, up to 24 calls/10 min, currently registered tools. Android grants and further app-level approvals still apply. STOP revokes." }.orEmpty()
        } }
        scope.launch { session.busy.collect { visibility = if (it) View.VISIBLE else View.GONE } }
    }
    fun shutdown() = scope.cancel()
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
