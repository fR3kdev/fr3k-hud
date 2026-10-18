package com.mcpintelligence.fr3k.appcontrol

import com.android.tools.smali.dexlib2.*
import com.android.tools.smali.dexlib2.iface.*
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.*
import com.android.tools.smali.dexlib2.rewriter.*
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.value.ValueType
import com.mcpintelligence.fr3k.appcontrol.analysis.*
import com.mcpintelligence.fr3k.capability.appmod.*
import java.io.File
import java.io.OutputStream

/** Executes data-only operations. No script, compiler, plugin or model is invoked here. */
object BoundedPatchExecutor {
    fun patch(apk: File, spec: PatchSpec, index: Int, output: OutputStream, clonePackage: String? = null, helperDex: ByteArray? = null) {
        ApkAccess.open(apk).use { patch(it, spec, index, output, clonePackage, helperDex) }
    }
    fun patch(apk: ApkAccess, spec: PatchSpec, index: Int, output: OutputStream, clonePackage: String? = null, helperDex: ByteArray? = null) {
        val module = apk.module
        run {
            require(spec.changes.size in 1..32)
            val relevant = spec.changes.filter { c -> when (c) {
                is PatchChange.ReplaceResourceString -> c.resourceId.startsWith("$index/")
                is PatchChange.ReplaceResourceColor -> c.resourceId.startsWith("$index/")
                is PatchChange.ReplaceDexConstant -> c.descriptor.startsWith("$index/")
                is PatchChange.SetLayoutAttribute -> c.resourceId.startsWith("$index/")
                is PatchChange.InsertHelperCall -> c.descriptor.startsWith("$index/")
                is PatchChange.ApplyHookTemplate -> false // Handled by the qualified LSPatch coordinator.
            } }
            val resourceEntries = ResourceAnalyzer.entries(module).toList()
            for (change in relevant) when (change) {
                is PatchChange.ReplaceResourceString -> {
                    val entry = resourceEntries.single { ResourceAnalyzer.key(it) == change.resourceId.substringAfter('/') }
                    require(entry.valueType == ValueType.STRING && entry.valueAsString == change.expectedOriginal) { "Resource precondition changed" }
                    require(change.replacement.length <= 4096)
                    entry.setValueAsString(change.replacement)
                }
                is PatchChange.ReplaceResourceColor -> {
                    val entry = resourceEntries.single { ResourceAnalyzer.key(it) == change.resourceId.substringAfter('/') }
                    require(entry.valueType.isColor && entry.resValue.data.toLong().and(0xffffffffL) == change.expectedOriginalArgb) { "Color precondition changed" }
                    require(change.replacementArgb in 0..0xffffffffL)
                    entry.setValueAsRaw(ValueType.COLOR_ARGB8, change.replacementArgb.toInt())
                }
                is PatchChange.SetLayoutAttribute -> patchLayout(module, change)
                else -> Unit
            }
            val dexChanges = relevant.filterIsInstance<PatchChange.ReplaceDexConstant>()
            val helperChanges = relevant.filterIsInstance<PatchChange.InsertHelperCall>()
            if (dexChanges.isNotEmpty() || helperChanges.isNotEmpty()) rewriteDex(apk, module, index, dexChanges, helperChanges)
            if (index == 0 && spec.changes.any { it is PatchChange.InsertHelperCall }) {
                val payload = requireNotNull(helperDex) { "Reviewed helper payload missing" }
                val numbers = module.listDexFiles().map { it.name.substringAfter("classes").substringBefore(".dex").toIntOrNull() ?: 1 }
                val next = (numbers.maxOrNull() ?: 0) + 1
                module.add(ByteInputSource(payload, if (next == 1) "classes.dex" else "classes$next.dex"))
            }
            if (clonePackage != null) {
                val original = module.packageName
                require(original in setOf("com.mcpintelligence.fr3k.patchfixture", "com.mcpintelligence.fr3k.patchfixturetwo")) { "No qualified side-by-side recipe for this package" }
                require(clonePackage == "$original.hudmod")
                // Only these owned fixtures are qualified: no provider, shared UID or custom permissions.
                val manifest = module.androidManifest
                require(manifest.listApplicationElementsByTag("provider").isEmpty())
                for (tag in listOf("activity", "activity-alias", "service", "receiver")) for (element in manifest.listApplicationElementsByTag(tag)) {
                    val attribute = element.getOrCreateAndroidAttribute("name", 0x01010003)
                    val old = attribute.valueString.orEmpty()
                    if (old.startsWith('.')) attribute.setValueAsString(original + old)
                }
                val appClass = manifest.applicationClassName
                if (appClass != null && appClass.startsWith('.')) manifest.setApplicationClassName(original + appClass)
                manifest.packageName = clonePackage
            }
            // Strip signing metadata only; other META-INF files are application data.
            module.listInputSources().filter { it.alias.matches(Regex("META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))", RegexOption.IGNORE_CASE)) }
                .forEach { module.removeInputSource(it.alias) }
            module.apkSignatureBlock = null
            module.refreshTable(); module.refreshManifest()
            module.createApkStreamWriter(output).use { writer ->
                writer.zipAligner = com.reandroid.archive.writer.ZipAligner.apkAligner().apply {
                    setDefaultAlignment(4); setFileAlignment(java.util.regex.Pattern.compile("lib/[^/]+/[^/]+\\.so"), 16384)
                }
                writer.write()
            }
        }
    }

    private fun patchLayout(module: ApkModule, c: PatchChange.SetLayoutAttribute) {
        val pathAndId = c.resourceId.substringAfter('/').split('#')
        require(pathAndId.size == 2 && pathAndId[0].matches(Regex("res/layout[-a-z0-9]*/[a-z0-9_]+\\.xml")))
        val id = pathAndId[1].removePrefix("0x").toLong(16).toInt()
        require(c.attribute in setOf("visibility", "contentDescription"))
        val doc = module.loadResXmlDocument(pathAndId[0])
        val elements = doc.recursiveElements().asSequence().map { it as com.reandroid.arsc.chunk.xml.ResXmlElement }.filter { e ->
            e.attributes.asSequence().any { a -> a.nameId == 0x010100d0 && a.data == id }
        }.toList()
        require(elements.size == 1) { "Layout target is ambiguous" }
        val attrId = if (c.attribute == "visibility") 0x010100dc else 0x01010273
        val attribute = elements.single().getOrCreateAndroidAttribute(c.attribute, attrId)
        val old = if (c.attribute == "visibility") when (attribute.data) { 0 -> "0"; 1 -> "4"; 2 -> "8"; else -> error("Unsupported XML visibility") } else attribute.valueString
        require(old == c.expectedOriginal) { "Layout precondition changed" }
        if (c.attribute == "visibility") {
            val value = c.replacement.toInt(); require(value in setOf(0, 4, 8)); attribute.valueType = ValueType.DEC; attribute.data = value / 4
        } else attribute.setValueAsString(c.replacement.take(512))
        doc.refreshFull()
        module.add(ByteInputSource(doc.bytes, pathAndId[0]))
    }

    private fun rewriteDex(apk: ApkAccess, module: ApkModule, index: Int, changes: List<PatchChange.ReplaceDexConstant>, helpers: List<PatchChange.InsertHelperCall>) {
        val applied = HashSet<String>()
        for ((name, dex) in apk.dexes()) {
            val local = changes.filter { it.descriptor.substringAfter('/').substringBefore('|') == name }
            val localHelpers = helpers.filter { it.descriptor.substringAfter('/').substringBefore('|') == name }
            if (local.isEmpty() && localHelpers.isEmpty()) continue
            val rewritten = DexRewriter(object : RewriterModule() {
                override fun getMethodRewriter(rewriters: Rewriters) = Rewriter<Method> { m ->
                    val implementation = m.implementation ?: return@Rewriter m
                    var offset = 0
                    val instructions = implementation.instructions.map { instruction ->
                        val key = "$index/$name|${DexEvidence.methodKey(m)}#$offset"
                        offset += instruction.codeUnits
                        val change = local.singleOrNull { it.descriptor == key }
                        if (change == null) instruction else { require(applied.add(key)); replace(instruction, change) }
                    }
                    val rebuilt = ImmutableMethodImplementation(implementation.registerCount, instructions, implementation.tryBlocks, implementation.debugItems)
                    val selected = localHelpers.filter { it.descriptor.substringAfter('|').substringBefore('#') == DexEvidence.methodKey(m) }
                    val finalImpl = if (selected.isEmpty()) rebuilt else MutableMethodImplementation(rebuilt).also { mutable ->
                        for (helper in selected.sortedByDescending { it.instructionOffset.toInt() }) {
                            require(helper.helperTemplateId in setOf("url.clean", "preference.persist", "hud.share"))
                            require(m.returnType == "Ljava/lang/String;")
                            val targetIndex = mutable.instructions.indexOfFirst { it.location.codeAddress == helper.instructionOffset.toInt() }
                            require(targetIndex >= 0)
                            val target = mutable.instructions[targetIndex]
                            require(target.opcode == Opcode.RETURN_OBJECT)
                            val reg = (target as OneRegisterInstruction).registerA
                            val contextReg = implementation.registerCount - m.parameterTypes.map { if (it.toString() in setOf("J", "D")) 2 else 1 }.sum()
                            val invoke = if (helper.helperTemplateId == "url.clean") {
                                BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, reg, 1, ImmutableMethodReference("Lcom/fr3k/appmodpayload/Helpers;", "cleanUrl", listOf("Ljava/lang/String;"), "Ljava/lang/String;"))
                            } else {
                                require(m.parameterTypes.firstOrNull()?.toString() == "Landroid/content/Context;" && contextReg in 0..15 && reg in 0..15)
                                if (helper.helperTemplateId == "preference.persist") {
                                    require(m.parameterTypes.getOrNull(1)?.toString() == "Ljava/lang/String;" && contextReg < 15)
                                    BuilderInstruction35c(Opcode.INVOKE_STATIC, 3, contextReg, contextReg + 1, reg, 0, 0,
                                        ImmutableMethodReference("Lcom/fr3k/appmodpayload/Helpers;", "persist", listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;"), "Ljava/lang/String;"))
                                } else BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, contextReg, reg, 0, 0, 0,
                                    ImmutableMethodReference("Lcom/fr3k/appmodpayload/Helpers;", "share", listOf("Landroid/content/Context;", "Ljava/lang/String;"), "Ljava/lang/String;"))
                            }
                            // Preserve branch labels at the original return location.
                            mutable.replaceInstruction(targetIndex, invoke)
                            mutable.addInstruction(targetIndex + 1, BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, reg))
                            mutable.addInstruction(targetIndex + 2, BuilderInstruction11x(Opcode.RETURN_OBJECT, reg))
                            require(applied.add(helper.descriptor))
                        }
                    }
                    ImmutableMethod(m.definingClass, m.name, m.parameters, m.returnType, m.accessFlags, m.annotations, m.hiddenApiRestrictions, finalImpl)
                }
            }).dexFileRewriter.rewrite(dex)
            val data = MemoryDataStore()
            try {
                DexPool.writeTo(data, rewritten)
                module.add(ByteInputSource(data.data, name))
            } finally { data.close() }
        }
        require(applied.size == changes.size + helpers.size) { "DEX targets missing or ambiguous" }
    }

    private fun replace(ins: Instruction, c: PatchChange.ReplaceDexConstant): Instruction {
        require(c.replacement.length <= 4096)
        val register = (ins as? OneRegisterInstruction)?.registerA ?: error("Not a supported constant instruction")
        return if (ins.opcode == Opcode.CONST_STRING || ins.opcode == Opcode.CONST_STRING_JUMBO) {
            require(c.expectedOriginal == "string:" + ((ins as ReferenceInstruction).reference as StringReference).string) { "DEX string precondition changed" }
            val ref = ImmutableStringReference(c.replacement)
            if (ins.opcode == Opcode.CONST_STRING) ImmutableInstruction21c(ins.opcode, register, ref) else ImmutableInstruction31c(ins.opcode, register, ref)
        } else {
            require(ins is NarrowLiteralInstruction && c.expectedOriginal == "int:${ins.narrowLiteral}") { "DEX numeric precondition changed" }
            val n = c.replacement.toInt()
            when (ins.opcode) {
                Opcode.CONST_4 -> { require(n in -8..7); ImmutableInstruction11n(ins.opcode, register, n) }
                Opcode.CONST_16 -> { require(n in Short.MIN_VALUE..Short.MAX_VALUE); ImmutableInstruction21s(ins.opcode, register, n) }
                Opcode.CONST -> ImmutableInstruction31i(ins.opcode, register, n)
                else -> error("Constant format not supported")
            }
        }
    }
}
