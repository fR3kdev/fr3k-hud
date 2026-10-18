package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import android.os.SystemClock
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.mcpintelligence.fr3k.capability.appmod.*
import com.mcpintelligence.fr3k.capability.sha256Hex
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.io.File
import java.security.cert.X509Certificate
import java.util.UUID

class AppModWorkflow(private val context: Context, val store: ModificationStore, private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime) {
    val artifacts = AppModArtifacts(context, store)
    private val lock = Mutex()
    private val approvals = mutableMapOf<String, Pair<String, Long>>()
    private fun jobDir(id: String): File { require(id.matches(Regex("[a-zA-Z0-9-]+"))); return File(context.noBackupFilesDir, "appmod/jobs/$id").apply { mkdirs() } }

    suspend fun analyze(sourceId: String, resumeJobId: String? = null): AnalysisReport = withContext(Dispatchers.IO) {
        val source = artifacts.get(sourceId)
        if (resumeJobId != null) {
            val previous = requireNotNull(store.getJob(resumeJobId))
            require(previous.artifact == source.artifact && previous.patchSpec == null)
            require(store.record("analysis_source", resumeJobId)?.getString("sourceId") == sourceId)
            store.getAnalysis(resumeJobId)?.let { return@withContext it }
        }
        val id = resumeJobId ?: UUID.randomUUID().toString(); val now = System.currentTimeMillis()
        var job = ModificationJob(id, source.artifact, ModificationStage.ANALYZING, now, now, id, null, null, null, null,
            VerificationStatus.UNVERIFIED, InstallationStatus.NOT_ATTEMPTED, null)
        store.saveJob(job); store.putRecord("analysis_source", id, JSONObject().put("sourceId", sourceId))
        try {
            val result = File(jobDir(id), "analysis.json")
            AppModSandbox.run(context, source.files, listOf(result), JSONObject().put("operation", "analyze").put("analysisId", id).put("artifact", source.artifact.toJson()))
            require(result.length() <= 8 * 1024 * 1024)
            val report = AnalysisReport.fromJson(JSONObject(result.readText()))
            require(report.artifact == source.artifact && report.analysisId == id)
            store.saveAnalysis(report); job = job.copy(stage = ModificationStage.SELECTING, updatedAtMillis = System.currentTimeMillis()); store.saveJob(job)
            report
        } catch (failure: Exception) { store.saveJob(job.copy(stage = if (failure is CancellationException) ModificationStage.CANCELLED else ModificationStage.FAILED,
            updatedAtMillis = System.currentTimeMillis(), error = failure.message)); throw failure }
    }

    fun suggestions(analysisId: String, goal: String? = null): List<ModificationSuggestion> {
        val report = requireNotNull(store.getAnalysis(analysisId))
        val ai = store.record("ai_ideas", analysisId)?.optJSONArray("ideas")
        val values = AppModSuggestions.generate(report) + AppModResearch.cachedSuggestions(store, report) +
            (if (ai == null) emptyList() else (0 until ai.length()).map { ModificationSuggestion.fromJson(ai.getJSONObject(it)) })
        val all = SuggestionRanker.rank(values.distinctBy { it.suggestionId }, goal)
        val runnable = all.filter { it.readiness in setOf(Readiness.READY, Readiness.CAN_GENERATE) }
        // Reserve slots for observed interface values: library/helper findings must not crowd out
        // all editable labels. Goal-directed ranking still applies within each target family.
        val valuesFirst = runnable.filter { s -> report.evidence.singleOrNull { it.evidenceId == s.evidenceIds.singleOrNull() }
            ?.let { runCatching { JSONObject(it.feature).optString("type") in setOf("string", "color") }.getOrDefault(false) } == true }.take(6)
        val executable = (valuesFirst + runnable).distinctBy { it.suggestionId }.take(12)
        val ideas = all.filter { it.readiness !in setOf(Readiness.READY, Readiness.CAN_GENERATE) }.take(8)
        val ranked = SuggestionRanker.rank(executable + ideas, goal)
        store.replaceSuggestions(analysisId, ranked)
        return ranked
    }

    fun preview(analysisId: String, selections: JSONArray, installationMode: String = "auto"): JSONObject {
        require(selections.length() in 1..32)
        val report = requireNotNull(store.getAnalysis(analysisId))
        val source = artifacts.get(requireNotNull(store.record("analysis_source", analysisId)).getString("sourceId"))
        require(source.artifact == report.artifact)
        val available = store.getSuggestionsForAnalysis(analysisId).associateBy { it.suggestionId }
        val changes = ArrayList<PatchChange>(); val seen = HashSet<String>(); val targets = HashSet<String>()
        for (i in 0 until selections.length()) {
            val selection = selections.getJSONObject(i)
            require(selection.keys().asSequence().all { it in setOf("suggestionId", "replacement") }) { "Unknown selection argument" }
            require(selection.get("suggestionId") is String && selection.get("replacement") is String) { "Selection values must be strings" }
            val id = selection.getString("suggestionId")
            require(seen.add(id)) { "Duplicate selection" }
            val suggestion = requireNotNull(available[id]) { "Suggestion not in this analysis" }
            require(suggestion.readiness == Readiness.CAN_GENERATE || suggestion.readiness == Readiness.READY) { "Suggestion needs an adapter" }
            val evidence = report.evidence.single { it.evidenceId == suggestion.evidenceIds.single() }
            require(targets.add(evidence.evidenceId)) { "Conflicting changes address the same target" }
            val replacement = selection.getString("replacement"); require(replacement.length <= 4096)
            val data = JSONObject(evidence.feature)
            if (data.has("hook")) {
                require(JSONObject(replacement).length() == 0)
                changes += PatchChange.ApplyHookTemplate(data.getString("hook"), "{}"); continue
            }
            when (val ref = evidence.reference) {
                is EvidenceReference.Resource -> when (data.getString("type")) {
                    "string" -> changes += PatchChange.ReplaceResourceString(ref.resourceId, data.getString("value"), replacement)
                    "color" -> {
                        val argb = replacement.removePrefix("#")
                        require(argb.matches(Regex("[0-9a-fA-F]{8}"))) { "Colour requires exactly eight ARGB hexadecimal digits" }
                        changes += PatchChange.ReplaceResourceColor(ref.resourceId, data.getString("value").toLong(), argb.toLong(16))
                    }
                    "layout" -> { require(replacement.toInt() in setOf(0, 4, 8)); changes += PatchChange.SetLayoutAttribute(ref.resourceId, data.getString("attribute"), data.optString("value").ifEmpty { null }, replacement) }
                    else -> error("Resource type needs an adapter")
                }
                is EvidenceReference.DexDescriptor -> {
                    if (data.has("helpers")) {
                        val allowed = data.getJSONArray("helpers"); require((0 until allowed.length()).any { allowed.getString(it) == replacement })
                        changes += PatchChange.InsertHelperCall(replacement, ref.descriptor, requireNotNull(ref.instructionOffset))
                    } else {
                        val literal = data.getString("literal")
                        if (literal.startsWith("int:")) {
                            val n = replacement.toInt()
                            when (data.getString("opcode")) { "CONST_4" -> require(n in -8..7); "CONST_16" -> require(n in Short.MIN_VALUE..Short.MAX_VALUE) }
                        }
                        changes += PatchChange.ReplaceDexConstant(ref.descriptor, literal, replacement)
                    }
                }
                else -> error("Selection does not identify an executable target")
            }
        }
        require(installationMode in setOf("auto", "same_package", "side_by_side"))
        val hook = changes.any { it is PatchChange.ApplyHookTemplate }
        require(!hook || changes.size == 1) { "The qualified hook conflicts with resource/DEX/helper changes" }
        val cloneSupported = CloneProfiles.supports(context, source.artifact)
        val clone = installationMode == "side_by_side" || installationMode == "auto" && cloneSupported && !hook
        require(!hook || !clone) { "The qualified hook requires the original package identity" }
        require(!clone || cloneSupported) { "No qualified side-by-side recipe for this build" }
        val id = UUID.randomUUID().toString()
        val spec = PatchSpec(id, null, report.artifact.fingerprint, changes,
            listOf(PatchPrecondition("identity", "fingerprint", analysisId, report.artifact.fingerprint)), emptyList(), emptyList())
        val record = JSONObject().put("previewId", id).put("analysisId", analysisId).put("sourceId", source.id).put("spec", spec.toJson())
            .put("installationMode", if (clone) "side_by_side" else "same_package")
            .put("clonePackage", if (clone) "${source.artifact.packageName}.hudmod" else "")
            .put("warnings", JSONArray(listOf("Modified APK uses the phone's HUD signing key.",
                if (clone) "A separate app is installed; original private data is not copied." else "Original signer differs; replacing the original may require uninstalling it and losing its private data.",
                "Original APK archive restores executable bytes, not private app data. Behavior must be verified after installation.")))
        require(record.toString().toByteArray().size <= 60 * 1024) { "Selection exceeds the bounded job specification; choose fewer changes or shorter values" }
        record.put("digest", digest(record))
        store.saveSpec(spec); store.putRecord("preview", id, record)
        val now = System.currentTimeMillis()
        store.saveJob(ModificationJob(id, source.artifact, ModificationStage.PREVIEWING, now, now, analysisId, id, spec, null, null,
            VerificationStatus.UNVERIFIED, InstallationStatus.NOT_ATTEMPTED, null))
        return record
    }

    /** Only the product review screen invokes this; it is never an agent/MCP argument. */
    internal fun approveFromUi(previewId: String, displayedDigest: String): String = synchronized(approvals) {
        val preview = requireNotNull(store.record("preview", previewId))
        require(preview.getString("digest") == displayedDigest && digest(JSONObject(preview.toString()).apply { remove("digest") }) == displayedDigest)
        approvals.entries.removeAll { elapsedRealtime() - it.value.second >= 120_000 }
        val token = UUID.randomUUID().toString(); approvals[token] = displayedDigest to elapsedRealtime(); token
    }
    suspend fun prepare(previewId: String, approvalToken: String): ModificationJob = lock.withLock { withContext(Dispatchers.IO) {
        val preview = requireNotNull(store.record("preview", previewId)); val binding = synchronized(approvals) { approvals.remove(approvalToken) }
        require(binding != null && binding.first == preview.getString("digest") && elapsedRealtime() - binding.second in 0 until 120_000) { "Exact current UI approval required" }
        require(digest(JSONObject(preview.toString()).apply { remove("digest") }) == binding.first)
        var job = requireNotNull(store.getJob(previewId)); if (job.stage == ModificationStage.PREPARED) return@withContext job
        require(job.stage in setOf(ModificationStage.PREVIEWING, ModificationStage.INTERRUPTED, ModificationStage.FAILED, ModificationStage.CANCELLED))
        val source = artifacts.get(preview.getString("sourceId")); val spec = PatchSpec.fromJson(preview.getJSONObject("spec"))
        require(source.artifact.fingerprint == spec.identityFingerprint && job.patchSpec == spec)
        OriginalArchive(context).store(StagedTarget(source.artifact.packageName, source.files.first(), source.files.drop(1), source.artifact.splitSha256, source.artifact.identity))
        job = job.copy(stage = ModificationStage.PREPARING, updatedAtMillis = System.currentTimeMillis(), error = null); store.saveJob(job)
        val directory = jobDir(previewId)
        try {
            require(directory.usableSpace >= maxOf(256L * 1024 * 1024, source.files.sumOf { it.length() } * 4)) { "Insufficient space for original, staged and signed copies" }
            val unsigned = source.files.indices.map { File(directory, "unsigned-$it.apk") }
            val hookChange = spec.changes.singleOrNull() as? PatchChange.ApplyHookTemplate
            val hookResult = if (hookChange != null) {
                require(source.files.size == 1 && hookChange.hookTemplateId == "owned-banner-control" && hookChange.configuration == "{}" &&
                    source.artifact.splitSha256.single() == "7f6f84a470fbc4dd7c7e828c0bc2845e775a4cc803b5da8a85ffc36731f28db7")
                val module = File(directory, "reviewed-hook.apk")
                context.assets.open("app-control/owned-fixture-hook.apk").use { input -> module.outputStream().use { input.copyTo(it) } }
                require(ApkHashing.sha256(module) == "65842fadc40a147ddb12aa608468e72fad5093b52958a8d5ef85050d4326134d")
                LspatchPatchEngine(context).patch(PatchRequest(source.artifact.identity, source.files.single(), module, "Reviewed owned fixture hook"), directory).outputApk
            } else null
            if (hookResult == null) AppModSandbox.run(context, source.files, unsigned, JSONObject().put("operation", "patch").put("spec", spec.toJson()).put("clonePackage", preview.getString("clonePackage")))
            if (hookResult == null) {
                val readback = File(directory, "readback.json")
                AppModSandbox.run(context, source.files + unsigned, listOf(readback), JSONObject().put("operation", "validate")
                    .put("sourceCount", source.files.size).put("spec", spec.toJson()).put("clonePackage", preview.getString("clonePackage")))
                require(JSONObject(readback.readText()).getString("status") == "STATIC_READBACK_PASS")
            }
            currentCoroutineContext().ensureActive()
            val signer = LspatchPatchEngine.signingEntry()
            val signed = if (hookResult != null) listOf(File(directory, "signed-0.apk").apply { hookResult.copyTo(this, true) }) else unsigned.mapIndexed { i, file -> File(directory, "signed-$i.apk").apply {
                ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("hud-user", signer.privateKey, listOf(signer.certificate as X509Certificate)).build()))
                    .setInputApk(file).setOutputApk(this).setMinSdkVersion(31).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true).build().sign()
                require(ApkVerifier.Builder(this).setMinCheckedPlatformVersion(31).build().verify().isVerified) { "Output signature invalid" }
            } }
            currentCoroutineContext().ensureActive()
            val hashes = signed.map(ApkHashing::sha256)
            job = job.copy(stage = ModificationStage.PREPARED, updatedAtMillis = System.currentTimeMillis(), outputApkPath = signed.first().absolutePath,
                outputSha256 = hashes.first(), verification = VerificationStatus.STATIC_CHECKS_PASSED)
            store.publishPrepared(job, JSONObject().put("files", JSONArray(signed.map { it.absolutePath })).put("hashes", JSONArray(hashes)))
            job
        } catch (failure: Exception) {
            directory.listFiles()?.filter { it.name.startsWith("signed-") || it.name.startsWith("unsigned-") }?.forEach { it.delete() }
            store.saveJob(job.copy(stage = if (failure is CancellationException) ModificationStage.CANCELLED else ModificationStage.FAILED,
                updatedAtMillis = System.currentTimeMillis(), error = failure.message)); throw failure
        }
    } }
    fun outputs(jobId: String): List<File> {
        val record = requireNotNull(store.record("outputs", jobId)); val a = record.getJSONArray("files"); val hashes = record.getJSONArray("hashes")
        return (0 until a.length()).map { File(a.getString(it)).also { file -> require(file.canonicalPath.startsWith(jobDir(jobId).canonicalPath + "/") && ApkHashing.sha256(file) == hashes.getString(it)) } }
    }
    companion object {
        fun canonical(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            else -> error("Unsupported JSON value")
        }
        fun digest(value: JSONObject) = sha256Hex(canonical(value).toByteArray())
    }
}
