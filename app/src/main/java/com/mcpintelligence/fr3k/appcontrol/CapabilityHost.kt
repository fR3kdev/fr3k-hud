package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import com.mcpintelligence.fr3k.capability.AppIdentity
import com.mcpintelligence.fr3k.capability.EvidenceRecord
import com.mcpintelligence.fr3k.capability.Journal
import com.mcpintelligence.fr3k.capability.RegistryStore
import com.mcpintelligence.fr3k.capability.WorkflowSpec
import java.io.File

/** Single source of truth for the registry, the journal, the archive and the workflows. */
class CapabilityHost(private val context: Context) {

    private val journalFile: File get() = File(context.noBackupFilesDir, "app-control/journal.json")
    val archive: OriginalArchive = OriginalArchive(context)

    @Volatile private var cachedRegistry: RegistryStore? = null
    private val journal = Journal()

    fun registry(refresh: Boolean = false): RegistryStore {
        val current = cachedRegistry
        if (current != null && !refresh) return current
        val loaded = CapabilityCatalogue.discover(context)
        cachedRegistry = loaded
        return loaded
    }

    fun installed(): Map<String, AppIdentity> = CapabilityCatalogue.installed(context)

    fun journalSnapshot(): List<EvidenceRecord> {
        if (journal.records.isEmpty() && journalFile.isFile) {
            runCatching { Journal.fromJson(org.json.JSONArray(journalFile.readText())).records.forEach { journal.append(it) } }
        }
        return journal.records
    }

    fun record(entry: EvidenceRecord) {
        journal.append(entry)
        journalFile.parentFile?.mkdirs()
        journalFile.writeText(journal.toJson().toString())
    }

    fun workflows(): List<WorkflowSpec> = listOfNotNull(CapabilityCatalogue.demoWorkflow(installed()))

    fun identityReader(): (String) -> AppIdentity = { pkg -> AndroidIdentityReader.of(context, pkg) }

    private var runner: com.mcpintelligence.fr3k.workflow.WorkflowRunner? = null

    fun workflowRunner(): com.mcpintelligence.fr3k.workflow.WorkflowRunner =
        runner ?: com.mcpintelligence.fr3k.workflow.WorkflowRunner(
            this,
            com.mcpintelligence.fr3k.integrations.privileged.PrivilegedOpsClient(context)::execute,
        ).also { runner = it }

    fun reset() {
        cachedRegistry = null
    }
}
