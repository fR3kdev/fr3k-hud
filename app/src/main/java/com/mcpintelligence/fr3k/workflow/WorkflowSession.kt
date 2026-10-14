package com.mcpintelligence.fr3k.workflow

import com.mcpintelligence.fr3k.appcontrol.CapabilityHost
import com.mcpintelligence.fr3k.capability.CapabilityDescriptor
import com.mcpintelligence.fr3k.capability.WorkflowSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-lifetime execution status survives tab navigation and target-app foreground changes. */
class WorkflowSession(private val host: CapabilityHost) {
    val draft = MutableStateFlow<List<CapabilityDescriptor>>(emptyList())
    val busy = MutableStateFlow(false)
    val note = MutableStateFlow("")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Synchronized fun run(spec: WorkflowSpec) {
        if (busy.value) return
        busy.value = true
        note.value = "Checking all ${spec.steps.size} target identities…"
        scope.launch {
            try {
                val report = host.workflowRunner().run(spec) { index, total, capability -> note.value = "Step $index of $total · $capability" }
                note.value = "${if (report.ok) "DONE" else "FAILED"}: ${report.note}"
            } catch (cancelled: CancellationException) { note.value = "Cancelled; inspect Journal for completed steps."; throw cancelled }
              catch (error: Exception) { note.value = "REFUSED: ${error.message}" }
            finally { busy.value = false }
        }
    }
}
