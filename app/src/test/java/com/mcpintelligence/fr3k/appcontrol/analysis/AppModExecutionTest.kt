package com.mcpintelligence.fr3k.appcontrol.analysis

import com.mcpintelligence.fr3k.appcontrol.*
import com.mcpintelligence.fr3k.capability.AppIdentity
import com.mcpintelligence.fr3k.capability.appmod.*
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.*

class AppModExecutionTest {
    private fun fixture() = File("src/main/assets/app-control/appmod-media-fixture.apk")
    private fun artifact() = ArtifactIdentity(AppIdentity("com.mcpintelligence.fr3k.patchfixture", 1, "a".repeat(64), listOf(ApkHashing.sha256(fixture()))), SourceKind.IMPORTED_APK, 1, "fixture")
    private fun report() = ApkAnalyzer.analyze(fixture(), artifact(), "test")
    private fun spec(changes: List<PatchChange>) = PatchSpec("test", null, artifact().fingerprint, changes, emptyList(), emptyList(), emptyList())

    @Test fun resourceAndDexEditsRoundTripAndPreserveSource() {
        val hash = ApkHashing.sha256(fixture()); val evidence = report().evidence
        val text = evidence.first { it.reference is EvidenceReference.Resource && JSONObject(it.feature).optString("name") == "appmod_label" }
        val color = evidence.first { it.reference is EvidenceReference.Resource && JSONObject(it.feature).optString("name") == "appmod_accent" }
        val constant = evidence.first { it.evidenceId.startsWith("dex:") && (it.reference as? EvidenceReference.DexDescriptor)?.descriptor?.contains("->playbackDefault()I") == true }
        val changes = listOf(PatchChange.ReplaceResourceString("0/" + (text.reference as EvidenceReference.Resource).resourceId, "Media library", "My media"),
            PatchChange.ReplaceResourceColor("0/" + (color.reference as EvidenceReference.Resource).resourceId, 0xff336699L, 0xff112233L),
            PatchChange.ReplaceDexConstant("0/" + (constant.reference as EvidenceReference.DexDescriptor).descriptor, "int:3", "5"))
        val output = File.createTempFile("appmod", ".apk")
        try {
            output.outputStream().use { BoundedPatchExecutor.patch(fixture(), spec(changes), 0, it) }
            val after = ResourceAnalyzer.extract(output)
            assertTrue(after.any { JSONObject(it.feature).optString("value") == "My media" })
            assertTrue(after.any { JSONObject(it.feature).optString("value") == 0xff112233L.toString() })
            System.err.println("PATCHED_DEFAULTS=" + DexEvidence.extract(output, artifact().packageName).filter { (it.reference as? EvidenceReference.DexDescriptor)?.descriptor?.contains("playbackDefault") == true })
            assertTrue(DexEvidence.extract(output, artifact().packageName).any { it.feature.contains("int:5") && (it.reference as? EvidenceReference.DexDescriptor)?.descriptor?.contains("playbackDefault") == true })
            assertEquals(hash, ApkHashing.sha256(fixture()))
        } finally { output.delete() }
    }
    @Test fun helperInsertionPreservesReadableDexAndAddsReviewedPayload() {
        val target = report().evidence.single { it.evidenceId.startsWith("helper:") && (it.reference as EvidenceReference.DexDescriptor).descriptor.contains("->mediaUrl()") }
        val ref = target.reference as EvidenceReference.DexDescriptor
        val helper = File("build/generated/appmod/assets/appmod/classes.dex").readBytes()
        val output = File.createTempFile("appmod", ".apk")
        try {
            output.outputStream().use { BoundedPatchExecutor.patch(fixture(), spec(listOf(PatchChange.InsertHelperCall("url.clean", "0/${ref.descriptor}", ref.instructionOffset!!))), 0, it, helperDex = helper) }
            val container = DexFileFactory.loadDexContainer(output, Opcodes.forApi(35))
            System.err.println("PATCHED_CLASSES=" + container.dexEntryNames.associateWith { container.getEntry(it)!!.dexFile.classes.map { cls -> cls.type }.filter { cls -> cls.contains("fr3k") } })
            System.err.println("PATCHED_HELPER=" + container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.flatMap { it.methods }.filter { it.name == "mediaUrl" }.flatMap { it.implementation!!.instructions }.map { it.opcode })
            assertTrue(container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.any { it.type == "Lcom/fr3k/appmodpayload/Helpers;" })
            assertTrue(container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.flatMap { it.methods }.filter { it.name == "mediaUrl" }
                .flatMap { it.implementation!!.instructions }.any { it.opcode in setOf(com.android.tools.smali.dexlib2.Opcode.INVOKE_STATIC, com.android.tools.smali.dexlib2.Opcode.INVOKE_STATIC_RANGE) })
        } finally { output.delete() }
    }
    @Test fun visibilityUsesAndroidXmlEnumRatherThanRuntimeFlag() {
        val target = report().evidence.first { it.evidenceId.startsWith("layout:") }
        val ref = target.reference as EvidenceReference.Resource
        val output = File.createTempFile("visibility", ".apk")
        val change = PatchChange.SetLayoutAttribute("0/${ref.resourceId}", "visibility", "0", "8")
        try {
            output.outputStream().use { BoundedPatchExecutor.patch(fixture(), spec(listOf(change)), 0, it) }
            ApkAccess.open(output).use { access ->
                val path = ref.resourceId.substringBefore('#')
                val id = ref.resourceId.substringAfter('#').toLong(16).toInt()
                val element = access.module.loadResXmlDocument(path).recursiveElements().asSequence().map { it as com.reandroid.arsc.chunk.xml.ResXmlElement }
                    .single { e -> e.attributes.asSequence().any { it.nameId == 0x010100d0 && it.data == id } }
                assertEquals(2, element.attributes.asSequence().single { it.nameId == 0x010100dc }.data)
            }
            assertEquals("8", JSONObject(ResourceAnalyzer.extract(output).single { it.reference == ref }.feature).getString("value"))
        } finally { output.delete() }
    }
    @Test fun wrongOriginalAndUnsupportedOperatorsAreRefused() {
        val ref = report().evidence.first { it.reference is EvidenceReference.Resource && JSONObject(it.feature).optString("name") == "appmod_label" }.reference as EvidenceReference.Resource
        val output = File.createTempFile("appmod", ".apk")
        try { assertThrows(IllegalArgumentException::class.java) { output.outputStream().use { BoundedPatchExecutor.patch(fixture(), spec(listOf(PatchChange.ReplaceResourceString("0/${ref.resourceId}", "wrong", "new"))), 0, it) } } }
        finally { output.delete() }
    }
    private fun labelChange(name: String = "appmod_label", replacement: String = "Approved label"): PatchChange.ReplaceResourceString {
        val e = report().evidence.first { it.reference is EvidenceReference.Resource && JSONObject(it.feature).optString("name") == name }
        return PatchChange.ReplaceResourceString("0/${(e.reference as EvidenceReference.Resource).resourceId}", JSONObject(e.feature).getString("value"), replacement)
    }
    private fun constantChange(method: String, replacement: String = "5"): PatchChange.ReplaceDexConstant {
        val e = report().evidence.first { it.evidenceId.startsWith("dex:") && (it.reference as? EvidenceReference.DexDescriptor)?.descriptor?.contains("->$method") == true }
        return PatchChange.ReplaceDexConstant("0/${(e.reference as EvidenceReference.DexDescriptor).descriptor}", JSONObject(e.feature).getString("literal"), replacement)
    }
    private fun output(changes: List<PatchChange>, block: (File) -> Unit) {
        val file = File.createTempFile("verify", ".apk")
        try {
            file.outputStream().use { BoundedPatchExecutor.patch(fixture(), spec(changes), 0, it, helperDex = File("build/generated/appmod/assets/appmod/classes.dex").readBytes()) }
            block(file)
        } finally { file.delete() }
    }
    private fun alterZip(input: File, name: String, value: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("tampered", ".apk")
        try {
            ZipFile(input).use { zip -> ZipOutputStream(file.outputStream()).use { out ->
                zip.entries().asSequence().filter { it.name != name }.forEach { entry ->
                    out.putNextEntry(ZipEntry(entry.name)); zip.getInputStream(entry).use { it.copyTo(out) }; out.closeEntry()
                }
                out.putNextEntry(ZipEntry(name)); out.write(value); out.closeEntry()
            } }
            block(file)
        } finally { file.delete() }
    }
    @Test fun exactVerifierAcceptsCombinedChanges() {
        val helperTarget = report().evidence.single { it.evidenceId.startsWith("helper:") && (it.reference as EvidenceReference.DexDescriptor).descriptor.contains("->mediaUrl()") }.reference as EvidenceReference.DexDescriptor
        val changes = listOf(labelChange(), constantChange("playbackDefault()I"), PatchChange.InsertHelperCall("url.clean", "0/${helperTarget.descriptor}", helperTarget.instructionOffset!!))
        output(changes) { file -> assertEquals("STATIC_READBACK_PASS", PatchVerifier.verify(listOf(fixture()), listOf(file), spec(changes), null, File("build/generated/appmod/assets/appmod/classes.dex").readBytes()).getString("status")) }
    }
    @Test fun exactVerifierAcceptsAllHelperSequencesAndRejectsWrongAddressBindings() {
        for (helper in listOf("url.clean", "preference.persist", "hud.share")) {
            val target = report().evidence.single { it.evidenceId.startsWith("helper:") && (it.reference as EvidenceReference.DexDescriptor).descriptor.contains(if (helper == "url.clean") "->mediaUrl()" else "->contextDefault(") }.reference as EvidenceReference.DexDescriptor
            val change = PatchChange.InsertHelperCall(helper, "0/${target.descriptor}", target.instructionOffset!!)
            output(listOf(change)) { file ->
                val payload = File("build/generated/appmod/assets/appmod/classes.dex").readBytes()
                assertEquals("STATIC_READBACK_PASS", PatchVerifier.verify(listOf(fixture()), listOf(file), spec(listOf(change)), null, payload).getString("status"))
                assertThrows(IllegalArgumentException::class.java) { PatchVerifier.verify(listOf(fixture()), listOf(file), spec(listOf(change.copy(instructionOffset = "999"))), null, payload) }
                assertThrows(IllegalArgumentException::class.java) { PatchVerifier.verify(listOf(fixture()), listOf(file), spec(listOf(change.copy(descriptor = "9/${target.descriptor}"))), null, payload) }
            }
        }
    }
    @Test fun exactVerifierRejectsUnrelatedResourceMutation() {
        val approved = constantChange("playbackDefault()I")
        output(listOf(approved, labelChange())) { file -> assertThrows(IllegalArgumentException::class.java) { PatchVerifier.verify(listOf(fixture()), listOf(file), spec(listOf(approved)), null) } }
    }
    @Test fun exactVerifierRejectsUnrelatedMethodMutation() {
        val approved = labelChange()
        output(listOf(approved, constantChange("playbackDefault()I"))) { file -> assertThrows(IllegalArgumentException::class.java) { PatchVerifier.verify(listOf(fixture()), listOf(file), spec(listOf(approved)), null) } }
    }
    @Test fun exactVerifierRejectsUnexpectedFileAndManifest() {
        val changes = listOf(labelChange())
        output(changes) { file ->
            for ((name, bytes) in listOf("assets/unapproved.txt" to byteArrayOf(1), "META-INF/unapproved.txt" to byteArrayOf(1), "AndroidManifest.xml" to ZipFile(File("src/main/assets/app-control/appmod-gallery-fixture.apk")).use { it.getInputStream(it.getEntry("AndroidManifest.xml")).readBytes() })) {
                alterZip(file, name, bytes) { altered -> assertThrows(IllegalArgumentException::class.java) { PatchVerifier.verify(listOf(fixture()), listOf(altered), spec(changes), null) } }
            }
        }
    }
    @Test fun exactVerifierRejectsUnchangedTargetWithReplacementElsewhereInMethod() {
        val change = constantChange("playbackDefault()I")
        val access = ApkAccess.open(fixture())
        try {
            val dex = access.dexes().first().second
            val rewritten = com.android.tools.smali.dexlib2.rewriter.DexRewriter(object : com.android.tools.smali.dexlib2.rewriter.RewriterModule() {
                override fun getMethodRewriter(r: com.android.tools.smali.dexlib2.rewriter.Rewriters) = com.android.tools.smali.dexlib2.rewriter.Rewriter<com.android.tools.smali.dexlib2.iface.Method> { m ->
                    if (m.name != "playbackDefault") m else {
                        val impl = com.android.tools.smali.dexlib2.builder.MutableMethodImplementation(m.implementation!!)
                        impl.addInstruction(com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n(com.android.tools.smali.dexlib2.Opcode.CONST_4, 0, 5))
                        com.android.tools.smali.dexlib2.immutable.ImmutableMethod(m.definingClass, m.name, m.parameters, m.returnType, m.accessFlags, m.annotations, m.hiddenApiRestrictions, impl)
                    }
                }
            }).dexFileRewriter.rewrite(dex)
            val data = com.android.tools.smali.dexlib2.writer.io.MemoryDataStore()
            try {
                com.android.tools.smali.dexlib2.writer.pool.DexPool.writeTo(data, rewritten)
                alterZip(fixture(), "classes.dex", data.data) { file -> assertThrows(IllegalArgumentException::class.java) { PatchVerifier.verify(listOf(fixture()), listOf(file), spec(listOf(change)), null) } }
            } finally { data.close() }
        } finally { access.close() }
    }
    @Test fun exactVerifierRequiresPackagedHelperBytes() {
        val target = report().evidence.single { it.evidenceId.startsWith("helper:") && (it.reference as EvidenceReference.DexDescriptor).descriptor.contains("->mediaUrl()") }.reference as EvidenceReference.DexDescriptor
        val changes = listOf(PatchChange.InsertHelperCall("url.clean", "0/${target.descriptor}", target.instructionOffset!!))
        output(changes) { file ->
            val reference = File("build/generated/appmod/assets/appmod/classes.dex").readBytes()
            assertThrows(IllegalArgumentException::class.java) { PatchVerifier.verify(listOf(fixture()), listOf(file), spec(changes), null, byteArrayOf(0)) }
            val incorrect = reference.copyOf()
            val needle = "utm_source".toByteArray()
            val offset = (0..incorrect.size - needle.size).first { start -> needle.indices.all { incorrect[start + it] == needle[it] } }
            incorrect[offset] = 'X'.code.toByte()
            alterZip(file, "classes2.dex", incorrect) { altered ->
                assertThrows(IllegalArgumentException::class.java) { PatchVerifier.verify(listOf(fixture()), listOf(altered), spec(changes), null, reference) }
            }
        }
    }
    @Test fun maliciousArchiveAndPrivacyLeakageAreRefused() {
        val bad = File.createTempFile("appmod", ".apk")
        try {
            ZipOutputStream(bad.outputStream()).use { it.putNextEntry(ZipEntry("../outside")); it.write(byteArrayOf(1)); it.closeEntry() }
            assertThrows(IllegalArgumentException::class.java) { ArchiveBudget.verify(bad) }
        } finally { bad.delete() }
        val original = report(); val secret = "PASSWORD=super-secret-value"
        val tainted = original.copy(evidence = original.evidence + FeatureEvidence(secret, EvidenceKind.OBSERVED, secret, EvidenceReference.DexDescriptor(secret, secret)))
        val summary = AppModResearch.summary(tainted, secret).toString()
        assertFalse(summary.contains("super-secret-value")); assertTrue(summary.toByteArray().size <= AppModResearch.SUMMARY_BYTES)
        val other = original.copy(analysisId = "second")
        assertTrue(AppModSuggestions.generate(original).map { it.suggestionId }.intersect(AppModSuggestions.generate(other).map { it.suggestionId }.toSet()).isEmpty())
    }
}
