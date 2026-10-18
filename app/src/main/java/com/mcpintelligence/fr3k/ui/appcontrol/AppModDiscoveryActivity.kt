package com.mcpintelligence.fr3k.ui.appcontrol

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.appcontrol.*
import com.mcpintelligence.fr3k.capability.appmod.*
import kotlinx.coroutines.*
import org.json.*

class AppModViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as Fr3kApplication
    val workflow = app.appModWorkflow
    var report by mutableStateOf<AnalysisReport?>(null)
    var suggestions by mutableStateOf<List<ModificationSuggestion>>(emptyList())
    var preview by mutableStateOf<JSONObject?>(null)
    var jobs by mutableStateOf<List<ModificationJob>>(emptyList())
    var message by mutableStateOf("Choose an APK or installed app to find improvements.")
    var busy by mutableStateOf(false)
    var installedApps by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var evidenceResults by mutableStateOf<List<FeatureEvidence>>(emptyList())
    private val draftLock = kotlinx.coroutines.sync.Mutex()
    private var task: Job? = null
    private var requestedPreview: String? = null
    private val prefs by lazy { app.getSharedPreferences("appmod.ui", 0) }
    init { viewModelScope.launch {
        val restored = withContext(Dispatchers.IO) {
            val id = prefs.getString("analysis", null)
            Triple(id?.let(workflow.store::getAnalysis), id?.let(workflow.store::getSuggestionsForAnalysis).orEmpty(),
                prefs.getString("preview", null)?.let { workflow.store.record("preview", it) })
        }
        // Apply restoration on Main after checking whether a newer UI request has taken ownership.
        if (requestedPreview == null && !busy && report == null) {
            report = restored.first; suggestions = restored.second; preview = restored.third
        }
        while (isActive) { jobs = withContext(Dispatchers.IO) { workflow.store.listJobs() }; delay(750) }
    } }
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; task = viewModelScope.launch {
            try { block() } catch (cancelled: CancellationException) { message = "Cancelled. Originals and verified checkpoints retained." }
            catch (failure: Exception) { message = failure.message ?: "Operation failed" }
            finally { busy = false }
        }
    }
    fun cancel() { task?.cancel() }
    fun browseInstalled() = work {
        installedApps = withContext(Dispatchers.IO) { app.packageManager.getInstalledApplications(0).map { it.packageName to app.packageManager.getApplicationLabel(it).toString() }.sortedBy { it.second.lowercase() } }
    }
    fun searchEvidence(query: String) = work {
        evidenceResults = withContext(Dispatchers.IO) { workflow.store.searchFeatures(requireNotNull(report).analysisId, query) }
    }
    fun saveDraft(selection: String, mode: String, goal: String) {
        val id = report?.analysisId ?: return
        viewModelScope.launch { draftLock.lock(); try { withContext(Dispatchers.IO) {
            workflow.store.putRecord("draft", id, JSONObject().put("selection", selection).put("mode", mode).put("goal", goal))
        } } finally { draftLock.unlock() } }
    }
    fun imported(uris: List<Uri>) = work {
        val source = withContext(Dispatchers.IO) { workflow.artifacts.imported(uris) }; select(source.id)
    }
    fun installed(pkg: String) = work {
        val source = withContext(Dispatchers.IO) { workflow.artifacts.installed(pkg.trim()) }; select(source.id)
    }
    private suspend fun select(id: String) {
        withContext(Dispatchers.IO) { prefs.edit().putString("source", id).remove("preview").apply() }; preview = null
        message = "Analysing immutable APK copies…"; report = workflow.analyze(id)
        withContext(Dispatchers.IO) { prefs.edit().putString("analysis", report!!.analysisId).apply() }
        suggestions = withContext(Dispatchers.IO) { workflow.suggestions(report!!.analysisId) }
        message = "${report!!.artifact.label}: ${report!!.evidence.size} findings. ${if (report!!.completed) "Analysis completed within stated coverage." else "Partial analysis; inspect limits."}"
    }
    fun resumeAnalysis() = work { select(withContext(Dispatchers.IO) { requireNotNull(prefs.getString("source", null)) { "Choose a source first" } }) }
    fun reopenAnalysis(id: String) = work {
        val sourceId = withContext(Dispatchers.IO) { requireNotNull(workflow.store.record("analysis_source", id)).getString("sourceId") }
        report = workflow.analyze(sourceId, id)
        suggestions = withContext(Dispatchers.IO) { workflow.suggestions(id) }
        preview = null
        withContext(Dispatchers.IO) { prefs.edit().putString("analysis", id).putString("source", sourceId).remove("preview").apply() }
        message = "Reopened retained analysis and verified source identity."
    }
    fun refresh(goal: String) = work { suggestions = withContext(Dispatchers.IO) { workflow.suggestions(requireNotNull(report).analysisId, goal) } }
    fun research(repository: String, goal: String, ai: Boolean) = work {
        val analysis = requireNotNull(report)
        check(app.settings.settings.value.consentProfile != com.mcpintelligence.fr3k.core.ConsentLevel.LOCAL_ONLY) { "Local-only consent is enabled. Change privacy settings to use public research or remote AI." }
        if (repository.isNotBlank()) { withContext(Dispatchers.IO) { AppModResearch.confirmRepository(workflow.store, analysis, repository.trim()) }; AppModResearch.refresh(workflow.store, analysis) }
        if (ai) AppModResearch.aiIdeas(workflow.store, analysis, app.routingProvider, goal)
        suggestions = withContext(Dispatchers.IO) { workflow.suggestions(analysis.analysisId, goal) }
        message = "Research saved. Public requests and AI ideas do not establish executable compatibility."
    }
    fun makePreview(selections: JSONArray, mode: String) = work {
        preview = withContext(Dispatchers.IO) { workflow.preview(requireNotNull(report).analysisId, selections, mode) }
        withContext(Dispatchers.IO) { prefs.edit().putString("preview", preview!!.getString("previewId")).apply() }; message = "Review exact changes and installation consequences."
    }
    fun openPreview(id: String) {
        requestedPreview = id
        work {
            val loaded = withContext(Dispatchers.IO) { requireNotNull(workflow.store.record("preview", id)) { "Preview no longer available" } }
            val analysisId = loaded.getString("analysisId")
            val restored = withContext(Dispatchers.IO) {
                workflow.store.getAnalysis(analysisId) to workflow.store.getSuggestionsForAnalysis(analysisId)
            }
            report = restored.first; suggestions = restored.second
            preview = loaded
            withContext(Dispatchers.IO) { prefs.edit().putString("preview", id).putString("analysis", analysisId).putString("source", loaded.getString("sourceId")).apply() }
        }
    }
    fun prepare() = work {
        val review = requireNotNull(preview); val id = review.getString("previewId")
        val token = withContext(Dispatchers.IO) { workflow.approveFromUi(id, review.getString("digest")) }
        message = "Preparing staged copy…"; val result = workflow.prepare(id, token)
        message = "PREPARED: ${result.outputSha256}. Static checks passed. Installation and behavior remain separate."
    }
    fun install(id: String, original: Boolean) = work {
        withContext(Dispatchers.IO) { AppModInstaller.install(app, workflow, id, original) }; message = "Android installer opened; review its confirmation."
    }
    fun reviewCloneRemoval(id: String) = work {
        val clone = withContext(Dispatchers.IO) { requireNotNull(workflow.store.record("preview", id)).getString("clonePackage") }
        require(clone.isNotEmpty()) { "This job did not create a separate app" }
        app.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$clone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        message = "Review the separate app in Android settings. Original installation and private data are separate."
    }
    fun verify(id: String) = work { message = withContext(Dispatchers.IO) { if (AppModInstaller.verifyInstalled(app, workflow, id)) "Installed artifact matches the expected hashes and signer." else "Expected artifact is not installed." } }
    fun userConfirmed(id: String) = work { withContext(Dispatchers.IO) {
        require(AppModInstaller.verifyInstalled(app, workflow, id)) { "Verify installation first" }
        require(workflow.store.record("install_session", id)?.getBoolean("original") == false) { "Original restoration is recorded separately from modified behavior" }
        val job = requireNotNull(workflow.store.getJob(id))
        workflow.store.saveJob(job.copy(stage = ModificationStage.COMPLETED, verification = VerificationStatus.USER_CONFIRMED, updatedAtMillis = System.currentTimeMillis()))
    }; message = "User observation recorded; no automated behavior PASS inferred." }
}

class AppModDiscoveryActivity : ComponentActivity() {
    private lateinit var vm: AppModViewModel
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.imported(it) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); vm = ViewModelProvider(this)[AppModViewModel::class.java]
        intent.getStringExtra("preview_id")?.let(vm::openPreview)
        setContent { com.mcpintelligence.fr3k.ui.Fr3kTheme { Surface(Modifier.fillMaxSize()) { DiscoveryScreen(vm, { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }, {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }, intent.hasExtra("preview_id")) } } }
    }
}

private fun LazyListScope.section(key: String, content: @Composable ColumnScope.() -> Unit) {
    item(key = key) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable private fun DiscoveryScreen(vm: AppModViewModel, pick: () -> Unit, allowInstall: () -> Unit, initialReviewOnly: Boolean) {
    var reviewOnly by rememberSaveable { mutableStateOf(initialReviewOnly) }
    var pkg by rememberSaveable { mutableStateOf("") }; var goal by rememberSaveable { mutableStateOf("") }
    var repository by rememberSaveable { mutableStateOf("") }; var mode by rememberSaveable { mutableStateOf("auto") }
    var selection by rememberSaveable { mutableStateOf("{}") }; var search by rememberSaveable { mutableStateOf("") }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var onlyExecutable by rememberSaveable { mutableStateOf(false) }
    var evidenceQuery by rememberSaveable { mutableStateOf("") }
    var showInstalled by rememberSaveable { mutableStateOf(false) }
    var installedQuery by rememberSaveable { mutableStateOf("") }
    var loadedDraft by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vm.report?.analysisId) {
        loadedDraft = null
        vm.report?.analysisId?.let { id ->
            val saved = withContext(Dispatchers.IO) { vm.workflow.store.record("draft", id) }
            selection = saved?.optString("selection", "{}") ?: "{}"
            mode = saved?.optString("mode", "auto") ?: "auto"; goal = saved?.optString("goal").orEmpty()
            loadedDraft = id
        }
    }
    LaunchedEffect(selection, mode, goal) { if (loadedDraft == vm.report?.analysisId && loadedDraft != null) vm.saveDraft(selection, mode, goal) }
    val listState = rememberLazyListState()
    // A saved preview opens at its source and digest, rather than inheriting the history's scroll offset.
    LaunchedEffect(reviewOnly) { listState.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).semantics {
        paneTitle = if (reviewOnly) "Review exact modification" else "Find app improvements"
    }, state = listState,
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        section("heading") {
        Text("Find app improvements", style = MaterialTheme.typography.headlineSmall)
        Text(vm.message, Modifier.testTag("appmod-status"))
        }
        if (reviewOnly) {
            section("review") {
            vm.preview?.let { preview ->
                Text("Review exact modification", style = MaterialTheme.typography.titleLarge)
                Text("Source: ${preview.getJSONObject("spec").getString("identityFingerprint")}")
                Text("Digest: ${preview.getString("digest")}")
                Text("Installation: ${preview.getString("installationMode")}")
                Text(preview.getJSONObject("spec").getJSONArray("changes").toString(2))
                Text(preview.getJSONArray("warnings").toString(2))
                Button(vm::prepare, enabled = !vm.busy, modifier = Modifier.testTag("appmod-approve")) { Text("Approve exact changes and prepare copy") }
                if (vm.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Button(vm::cancel) { Text("Cancel current work") } }
                val id = preview.getString("previewId")
                vm.jobs.singleOrNull { it.jobId == id }?.let { job ->
                    Text("${job.stage} · ${job.verification} · ${job.installerStatus}")
                    job.error?.let { Text(it) }
                    if (job.outputSha256 != null && job.previewId == vm.preview?.optString("previewId")) {
                        Text("Output SHA256: ${job.outputSha256}")
                        Button({ vm.install(id, false) }, enabled = !vm.busy) { Text("Review in Android installer") }
                        OutlinedButton({ vm.verify(id) }, enabled = !vm.busy) { Text("Check installed artifact") }
                        OutlinedButton({ vm.userConfirmed(id) }, enabled = !vm.busy) { Text("I checked the changed behavior") }
                        OutlinedButton({ vm.install(id, true) }, enabled = !vm.busy) { Text("Review archived original restoration") }
                        if (preview.getString("clonePackage").isNotEmpty()) OutlinedButton({ vm.reviewCloneRemoval(id) }, enabled = !vm.busy) { Text("Review removing the separate app") }
                    }
                }
            }
            }
            section("review-navigation") {
            TextButton({ reviewOnly = false }) { Text("Open analysis, saved jobs and offline guides") }
            OutlinedButton(allowInstall) { Text("Allow Android installation from HUD") }
            }
            return@LazyColumn
        }
        section("discovery-navigation") {
        if (vm.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Button(vm::cancel) { Text("Cancel current work") } }
        OutlinedButton({ showGuide = !showGuide }) { Text("Offline walkthrough and recovery") }
        vm.preview?.optString("previewId")?.takeIf { it.isNotBlank() }?.let { id ->
            OutlinedButton({ vm.openPreview(id); reviewOnly = true }, enabled = !vm.busy) {
                Text("Review saved preview / resume")
            }
        }
        }
        if (showGuide) appModGuides().forEach { guide ->
            section("guide-${guide.id}") {
            Text(guide.title, style = MaterialTheme.typography.titleMedium)
            guide.steps.forEachIndexed { index, step -> Text("${index + 1}. $step") }
            Text("Expected: complete the stated action and inspect its real status/receipt. ${guide.limits}")
            Text("Recovery: ${guide.reversible}")
            }
        }
        section("source-and-research") {
        OutlinedButton(allowInstall) { Text("Allow Android installation from HUD") }
        OutlinedButton({ (vm.getApplication<Application>() as Fr3kApplication).startActivity(Intent(vm.getApplication(), SelectedAppPatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Expert: import compatible Xposed module") }
        Button(pick, enabled = !vm.busy) { Text("Choose APK or base + split APKs") }
        OutlinedButton({ showInstalled = !showInstalled; if (showInstalled) vm.browseInstalled() }, enabled = !vm.busy) { Text("Choose installed app") }
        if (showInstalled) {
            OutlinedTextField(installedQuery, { installedQuery = it }, label = { Text("Find installed app") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Find installed app" })
            vm.installedApps.filter { it.first.contains(installedQuery, true) || it.second.contains(installedQuery, true) }.take(20).forEach { (packageName, label) ->
                TextButton({ pkg = packageName; showInstalled = false; vm.installed(packageName) }) { Text("$label · $packageName") }
            }
        }
        OutlinedTextField(pkg, { pkg = it }, label = { Text("Installed package") }, modifier = Modifier.fillMaxWidth().testTag("appmod-package").semantics { contentDescription = "Installed package" })
        Button({ vm.installed(pkg) }, enabled = !vm.busy && pkg.isNotBlank(), modifier = Modifier.testTag("appmod-analyze")) { Text("Analyse installed app") }
        OutlinedButton(vm::resumeAnalysis, enabled = !vm.busy) { Text("Resume source analysis") }
        OutlinedTextField(goal, { goal = it }, label = { Text("What would you like this app to do?") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "What would you like this app to do?" })
        Button({ vm.refresh(goal) }, enabled = vm.report != null && !vm.busy) { Text("Find local suggestions") }
        OutlinedTextField(repository, { repository = it }, label = { Text("Confirm public repository: owner/repository") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Confirm public repository: owner/repository" })
        Text("Public lookup sends the repository name. AI receives a bounded summary of package, technology and evidence categories; APKs and full code stay local.")
        Button({ vm.research(repository, goal, false) }, enabled = vm.report != null && repository.isNotBlank() && !vm.busy) { Text("Confirm repository and research requests") }
        OutlinedButton({ vm.research("", goal, true) }, enabled = vm.report != null && !vm.busy) { Text("Suggest with configured AI") }
        }
        vm.report?.let { report ->
            section("suggestion-search") {
            Text("${report.artifact.packageName} · ${report.artifact.fingerprint}")
            Text("Coverage: ${report.limits.joinToString("; ")}")
            OutlinedTextField(search, { search = it }, label = { Text("Search suggestions") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search suggestions" })
            Row { Switch(onlyExecutable, { onlyExecutable = it }, modifier = Modifier.semantics { contentDescription = "Show executable suggestions only" }); Text("Executable suggestions only") }
            TextButton({ showAll = !showAll }) { Text(if (showAll) "Show top eight" else "Show all suggestions (up to twenty)") }
            }
            items(vm.suggestions.filter { (!onlyExecutable || it.readiness in setOf(Readiness.READY, Readiness.CAN_GENERATE)) &&
                (it.title.contains(search, true) || it.proposedChange.contains(search, true)) }.take(if (showAll) 20 else 8),
                key = { "suggestion-${it.suggestionId}" }, contentType = { "suggestion" }) { suggestion ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(suggestion.title, style = MaterialTheme.typography.titleMedium)
                    Text("${suggestion.readiness} · ${suggestion.provenance} · ${suggestion.route}")
                    Text(suggestion.proposedChange); Text(suggestion.expectedBenefit)
                    Text(suggestion.compatibilityLimits.joinToString("\n")); suggestion.requestRefs.forEach { Text(it) }
                    Text("Recovery: ${suggestion.recovery}")
                    val editable = suggestion.route == ExecutionRoute.APK_PATCH && suggestion.readiness in setOf(Readiness.CAN_GENERATE, Readiness.READY)
                    if (editable) {
                        val selections = JSONObject(selection); val chosen = selections.has(suggestion.suggestionId)
                        Row { Checkbox(chosen, { checked ->
                            val copy = JSONObject(selection)
                            if (checked) copy.put(suggestion.suggestionId, suggestion.suggestedReplacement ?: "") else copy.remove(suggestion.suggestionId)
                            selection = copy.toString()
                        }, modifier = Modifier.semantics { contentDescription = "Select ${suggestion.title}" }); Text("Select change") }
                        val evidence = report.evidence.singleOrNull { it.evidenceId == suggestion.evidenceIds.singleOrNull() }
                        Text("Observed: ${evidence?.feature.orEmpty()}")
                        if (chosen) OutlinedTextField(selections.optString(suggestion.suggestionId), { value ->
                            selection = JSONObject(selection).put(suggestion.suggestionId, value).toString()
                        }, label = { Text("Replacement (colour: 8-digit ARGB hex)") }, modifier = Modifier.fillMaxWidth().testTag("appmod-replacement").semantics { contentDescription = "Replacement value for ${suggestion.title}" })
                    }
                } }
            }
            section("evidence-and-preview") {
            OutlinedTextField(evidenceQuery, { evidenceQuery = it }, label = { Text("Search local feature evidence") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search local feature evidence" })
            OutlinedButton({ vm.searchEvidence(evidenceQuery) }, enabled = !vm.busy) { Text("Inspect feature map") }
            vm.evidenceResults.forEach { evidence -> Text("${evidence.kind} · ${evidence.evidenceId}\n${evidence.feature}") }
            Text("Installation preference")
            for (option in listOf("auto", "same_package", "side_by_side")) Row { RadioButton(mode == option, { mode = option }, modifier = Modifier.semantics { contentDescription = "Installation ${option.replace('_', ' ')}" }); Text(option.replace('_', ' ')) }
            Button({ val chosen = JSONObject(selection); val array = JSONArray()
                chosen.keys().forEach { array.put(JSONObject().put("suggestionId", it).put("replacement", chosen.getString(it))) }
                vm.makePreview(array, mode)
            }, enabled = JSONObject(selection).length() > 0 && !vm.busy) { Text("Preview selected changes") }
            }
        }
        vm.preview?.let { preview ->
            section("preview") {
            Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Review modification", style = MaterialTheme.typography.titleLarge)
                Text("Digest: ${preview.getString("digest")}"); Text("Mode: ${preview.getString("installationMode")}")
                Text(preview.getJSONObject("spec").getJSONArray("changes").toString(2))
                Text(preview.getJSONArray("warnings").toString(2))
                Button(vm::prepare, enabled = !vm.busy, modifier = Modifier.testTag("appmod-approve")) { Text("Approve exact changes and prepare copy") }
            } }
            }
        }
        item(key = "jobs-heading") { Text("Durable jobs", style = MaterialTheme.typography.titleLarge) }
        items(vm.jobs.take(20), key = { "job-${it.jobId}" }, contentType = { "job" }) { job -> Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${job.artifact?.packageName} · ${job.stage}\n${job.jobId}\n${job.verification} · ${job.installerStatus}")
            job.error?.let { Text(it) }
            if (job.patchSpec == null) OutlinedButton({ vm.reopenAnalysis(job.jobId) }, enabled = !vm.busy) { Text("Reopen analysis / resume interrupted analysis") }
            if (job.previewId != null) OutlinedButton({ vm.openPreview(requireNotNull(job.previewId)); reviewOnly = true }, enabled = !vm.busy) { Text("Review saved preview / resume") }
            job.outputSha256?.let { Text("Output SHA256: $it") }
        } } }
    }
}
