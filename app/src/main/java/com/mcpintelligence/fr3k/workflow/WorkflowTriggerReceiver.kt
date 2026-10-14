package com.mcpintelligence.fr3k.workflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mcpintelligence.fr3k.Fr3kApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Manual trigger surface: a button, a Hermes tool call, or an adb broadcast all land here. */
class WorkflowTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_WORKFLOW) return
        val app = context.applicationContext as? Fr3kApplication ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val host = app.capabilityHost
                val spec = host.workflows().firstOrNull { it.id == (intent.getStringExtra("workflow") ?: it.id) }
                if (spec == null) {
                    Log.i(TAG, "no workflow matched")
                    return@launch
                }
                val report = withTimeout(120_000) { host.workflowRunner().run(spec) }
                Log.i(TAG, "workflow ${report.workflowId} ok=${report.ok} ${report.note}")
            } catch (error: Exception) {
                Log.i(TAG, "workflow failed: ${error.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "FR3KWF"
        const val ACTION_RUN_WORKFLOW = "com.mcpintelligence.fr3k.hud.RUN_WORKFLOW"
    }
}
