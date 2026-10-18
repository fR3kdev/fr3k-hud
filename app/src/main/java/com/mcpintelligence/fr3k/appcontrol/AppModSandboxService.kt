package com.mcpintelligence.fr3k.appcontrol

import android.app.Service
import android.content.Intent
import android.os.*
import com.mcpintelligence.fr3k.appcontrol.analysis.ApkAnalyzer
import com.mcpintelligence.fr3k.capability.appmod.*
import org.json.JSONObject
import java.io.File

/** No keys, network, application storage paths or dynamic code loading in this process. */
class AppModSandboxService : Service() {
    override fun onBind(intent: Intent) = object : IAppModSandbox.Stub() {
        override fun cancel() { android.os.Process.killProcess(android.os.Process.myPid()) }
        @Synchronized override fun run(inputs: Array<ParcelFileDescriptor>, outputs: Array<ParcelFileDescriptor>, request: String): String {
            val timer = Handler(Looper.getMainLooper())
            val watchdog = Runnable { android.os.Process.killProcess(android.os.Process.myPid()) }
            timer.postDelayed(watchdog, 170_000)
            return try {
                require(request.toByteArray().size <= 65536 && inputs.size in 1..64 && outputs.size in 1..32)
                val json = JSONObject(request)
                val files = inputs.map { com.mcpintelligence.fr3k.appcontrol.analysis.ApkAccess.fromChannel(java.io.FileInputStream(it.fileDescriptor).channel) }
                when (json.getString("operation")) {
                    "validate" -> {
                        val count = json.getInt("sourceCount"); require(files.size == count * 2)
                        val result = PatchVerifier.verifyAccess(files.take(count), files.drop(count), PatchSpec.fromJson(json.getJSONObject("spec")), json.optString("clonePackage").ifEmpty { null },
                            if (PatchSpec.fromJson(json.getJSONObject("spec")).changes.any { it is PatchChange.InsertHelperCall }) assets.open("appmod/classes.dex").use { it.readBytes() } else null)
                        ParcelFileDescriptor.AutoCloseOutputStream(outputs.first()).use { it.write(result.toString().toByteArray()) }
                    }
                    "metadata" -> {
                        val metadata = org.json.JSONArray()
                        files.forEach { file ->
                            val manifest = com.mcpintelligence.fr3k.appcontrol.analysis.ManifestAnalyzer.parse(file.module)
                            val split = file.module.split.orEmpty()
                            metadata.put(JSONObject().put("package", manifest.packageName).put("version", manifest.versionCode).put("split", split))
                        }
                        ParcelFileDescriptor.AutoCloseOutputStream(outputs.first()).use { it.write(metadata.toString().toByteArray()) }
                    }
                    "analyze" -> {
                        val artifact = ArtifactIdentity.fromJson(json.getJSONObject("artifact"))
                        val reports = files.mapIndexed { i, file -> ApkAnalyzer.analyze(file, artifact, json.getString("analysisId")) }
                        val base = reports.first()
                        val report = base.copy(evidence = reports.flatMapIndexed { i, r -> r.evidence.map { e ->
                            e.copy(evidenceId = "$i:${e.evidenceId}", reference = when (val ref = e.reference) {
                                is EvidenceReference.Resource -> EvidenceReference.Resource("$i/${ref.resourceId}")
                                is EvidenceReference.DexDescriptor -> EvidenceReference.DexDescriptor("$i/${ref.descriptor}", ref.instructionOffset)
                                else -> ref
                            })
                        } }, technology = reports.flatMap { it.technology }.toSet() + if (files.size > 1) setOf(TechnologyIndicator.SPLIT_SET) else emptySet(),
                            completed = reports.all { it.completed }, limits = reports.flatMap { it.limits }.distinct())
                        ParcelFileDescriptor.AutoCloseOutputStream(outputs.first()).use { it.write(report.toJson().toString().toByteArray()) }
                    }
                    "patch" -> {
                        val spec = PatchSpec.fromJson(json.getJSONObject("spec"))
                        require(outputs.size == files.size)
                        files.forEachIndexed { i, file -> ParcelFileDescriptor.AutoCloseOutputStream(outputs[i]).use { output ->
                            BoundedPatchExecutor.patch(file, spec, i, output, json.optString("clonePackage").ifEmpty { null },
                                if (spec.changes.any { it is PatchChange.InsertHelperCall }) assets.open("appmod/classes.dex").use { it.readBytes() } else null)
                        } }
                    }
                    else -> error("Unsupported sandbox operation")
                }
                files.forEach { it.close() }
                JSONObject().put("ok", true).toString()
            } catch (failure: Exception) { JSONObject().put("ok", false).put("error", failure.message ?: failure.javaClass.simpleName).toString() }
            finally { timer.removeCallbacks(watchdog); inputs.forEach { runCatching { it.close() } }; outputs.forEach { runCatching { it.close() } } }
        }
    }
}
