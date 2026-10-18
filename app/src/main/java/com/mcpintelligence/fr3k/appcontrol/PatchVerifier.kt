package com.mcpintelligence.fr3k.appcontrol

import com.mcpintelligence.fr3k.appcontrol.analysis.*
import com.mcpintelligence.fr3k.capability.appmod.*
import com.android.tools.smali.dexlib2.*
import com.android.tools.smali.dexlib2.iface.*
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.*
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.reference.*
import com.android.tools.smali.dexlib2.rewriter.*
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.reandroid.arsc.value.ValueType
import org.json.JSONObject
import java.io.File

/** Reconstruct the approved semantics from the immutable input; compare the entire output. */
object PatchVerifier {
    fun verify(before: List<File>, after: List<File>, spec: PatchSpec, clone: String?, helperDex: ByteArray? = null): JSONObject {
        val inputs = (before + after).map(ApkAccess::open)
        return try { verifyAccess(inputs.take(before.size), inputs.drop(before.size), spec, clone, helperDex) }
        finally { inputs.forEach { it.close() } }
    }
    private fun canonical(dex: DexFile): ByteArray {
        val data = MemoryDataStore()
        return try { DexPool.writeTo(data, dex); data.data } finally { data.close() }
    }
    fun verifyAccess(before: List<ApkAccess>, after: List<ApkAccess>, spec: PatchSpec, clone: String?, helperDex: ByteArray? = null): JSONObject {
        require(before.size == after.size && before.isNotEmpty())
        require(spec.changes.none { it is PatchChange.ApplyHookTemplate }) { "Hook requires its exact-source verification route" }
        fun signature(name: String) = name.matches(Regex("META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))", RegexOption.IGNORE_CASE))
        val helper = spec.changes.any { it is PatchChange.InsertHelperCall }
        val targets = mutableSetOf<String>()
        for (change in spec.changes) {
            val target = when (change) {
                is PatchChange.ReplaceResourceString -> "res:${change.resourceId}"
                is PatchChange.ReplaceResourceColor -> "res:${change.resourceId}"
                is PatchChange.SetLayoutAttribute -> "layout:${change.resourceId}:${change.attribute}"
                is PatchChange.ReplaceDexConstant -> "dex:${change.descriptor}"
                is PatchChange.InsertHelperCall -> {
                    require(change.descriptor.substringAfterLast('#') == change.instructionOffset) { "Helper address binding differs" }
                    "dex:${change.descriptor}"
                }
                else -> error("Unsupported verifier route")
            }
            require(targets.add(target)) { "Duplicate approved target" }
            val descriptor = target.substringAfter(':')
            val index = descriptor.substringBefore('/').toInt()
            require(index in before.indices) { "APK target index out of range" }
            if (target.startsWith("dex:")) require(before[index].dexes().any { it.first == descriptor.substringAfter('/').substringBefore('|') }) { "Approved DEX missing" }
        }
        if (helper) require(before.none { apk -> apk.dexes().any { (_, dex) -> dex.classes.any { it.type == "Lcom/fr3k/appmodpayload/Helpers;" } } }) { "Helper payload already present in source set" }
        for (i in before.indices) {
            val old = before[i]; val new = after[i]
            val oldDex = old.dexes().toMap(); val newDex = new.dexes().toMap()
            val helperName = if (i == 0 && helper) {
                val next = (oldDex.keys.map { it.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 1 }.maxOrNull() ?: 0) + 1
                if (next == 1) "classes.dex" else "classes$next.dex"
            } else null
            require(new.sources.all { entry -> old.sources.any { it.alias == entry.alias } || entry.alias == helperName }) { "Unexpected archive entry" }
            val names = old.sources.map { it.alias }.filterNot { signature(it) }.toSet() + listOfNotNull(helperName)
            require(new.sources.map { it.alias }.filterNot { signature(it) }.toSet() == names) { "Unexpected archive inventory: expected=$names actual=${new.sources.map { it.alias }.filterNot { signature(it) }.toSet()}" }
            require(newDex.keys == oldDex.keys + listOfNotNull(helperName)) { "Unexpected DEX inventory" }
            if (helperName != null) {
                require(oldDex.values.flatMap { it.classes }.none { it.type == "Lcom/fr3k/appmodpayload/Helpers;" })
                val payload = requireNotNull(helperDex) { "Packaged helper reference required" }
                require(new.sources.single { it.alias == helperName }.openStream().use { it.readBytes() }.contentEquals(payload)) { "Helper payload differs from packaged reference" }
            }
            for ((name, dex) in oldDex) {
                val changes = spec.changes.filterIsInstance<PatchChange.ReplaceDexConstant>().filter { it.descriptor.startsWith("$i/$name|") }
                val helpers = spec.changes.filterIsInstance<PatchChange.InsertHelperCall>().filter { it.descriptor.startsWith("$i/$name|") }
                val consumed = mutableSetOf<String>()
                val expected = DexRewriter(object : RewriterModule() {
                    override fun getMethodRewriter(rewriters: Rewriters) = Rewriter<Method> { method ->
                        val impl = method.implementation ?: return@Rewriter method
                        val prefix = "$i/$name|${DexEvidence.methodKey(method)}#"
                        val edits = changes.filter { it.descriptor.startsWith(prefix) }
                        val inserts = helpers.filter { it.descriptor.startsWith(prefix) }
                        if (edits.isEmpty() && inserts.isEmpty()) return@Rewriter method
                        val mutable = MutableMethodImplementation(impl)
                        // Descending source addresses ensure earlier edits cannot shift later targets.
                        val offsets = (edits.map { it.descriptor.substringAfterLast('#').toInt() } + inserts.map { it.instructionOffset.toInt() }).distinct().sortedDescending()
                        for (address in offsets) {
                            val index = mutable.instructions.indexOfFirst { it.location.codeAddress == address }
                            require(index >= 0) { "Approved instruction missing" }
                            val instruction = mutable.instructions[index]
                            val constant = edits.singleOrNull { it.descriptor == prefix + address }
                            val insertion = inserts.singleOrNull { it.descriptor == prefix + address }
                            require(constant == null || insertion == null)
                            val reg = (instruction as? OneRegisterInstruction)?.registerA ?: error("Unsupported target")
                            if (constant != null) {
                                val c = constant
                                val replacement = if (c.expectedOriginal.startsWith("string:")) {
                                    require((instruction as ReferenceInstruction).reference.let { it is StringReference && "string:${it.string}" == c.expectedOriginal })
                                    val ref = ImmutableStringReference(c.replacement)
                                    when (instruction.opcode) {
                                        Opcode.CONST_STRING -> BuilderInstruction21c(Opcode.CONST_STRING, reg, ref)
                                        Opcode.CONST_STRING_JUMBO -> BuilderInstruction31c(Opcode.CONST_STRING_JUMBO, reg, ref)
                                        else -> error("Unsupported string constant")
                                    }
                                } else {
                                    require(instruction is NarrowLiteralInstruction && c.expectedOriginal == "int:${instruction.narrowLiteral}")
                                    val value = c.replacement.toInt()
                                    when (instruction.opcode) {
                                        Opcode.CONST_4 -> BuilderInstruction11n(Opcode.CONST_4, reg, value)
                                        Opcode.CONST_16 -> BuilderInstruction21s(Opcode.CONST_16, reg, value)
                                        Opcode.CONST -> BuilderInstruction31i(Opcode.CONST, reg, value)
                                        else -> error("Unsupported numeric constant")
                                    }
                                }
                                mutable.replaceInstruction(index, replacement); require(consumed.add(c.descriptor))
                            } else {
                                val h = requireNotNull(insertion)
                                require(h.instructionOffset.toInt() == address && instruction.opcode == Opcode.RETURN_OBJECT && method.returnType == "Ljava/lang/String;")
                                val context = impl.registerCount - method.parameterTypes.map { if (it.toString() in setOf("J", "D")) 2 else 1 }.sum()
                                val owner = "Lcom/fr3k/appmodpayload/Helpers;"
                                val invoke = when (h.helperTemplateId) {
                                    "url.clean" -> BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, reg, 1, ImmutableMethodReference(owner, "cleanUrl", listOf("Ljava/lang/String;"), "Ljava/lang/String;"))
                                    "preference.persist", "hud.share" -> {
                                        require(method.parameterTypes.firstOrNull()?.toString() == "Landroid/content/Context;" && context in 0..15 && reg in 0..15)
                                        if (h.helperTemplateId == "preference.persist") {
                                            require(method.parameterTypes.getOrNull(1)?.toString() == "Ljava/lang/String;" && context < 15)
                                            BuilderInstruction35c(Opcode.INVOKE_STATIC, 3, context, context + 1, reg, 0, 0, ImmutableMethodReference(owner, "persist", listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;"), "Ljava/lang/String;"))
                                        } else BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, context, reg, 0, 0, 0, ImmutableMethodReference(owner, "share", listOf("Landroid/content/Context;", "Ljava/lang/String;"), "Ljava/lang/String;"))
                                    }
                                    else -> error("Unknown helper")
                                }
                                mutable.replaceInstruction(index, invoke)
                                mutable.addInstruction(index + 1, BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, reg))
                                mutable.addInstruction(index + 2, BuilderInstruction11x(Opcode.RETURN_OBJECT, reg))
                                require(consumed.add(h.descriptor))
                            }
                        }
                        ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType, method.accessFlags, method.annotations, method.hiddenApiRestrictions, mutable)
                    }
                }).dexFileRewriter.rewrite(dex)
                val expectedBytes = canonical(expected)
                require(consumed.size == changes.size + helpers.size) { "Missing or duplicate approved DEX edit" }
                require(expectedBytes.contentEquals(canonical(newDex.getValue(name)))) { "Unexpected DEX semantics: $name" }
            }
            val entries = ResourceAnalyzer.entries(old.module).associateBy(ResourceAnalyzer::key)
            for (c in spec.changes) when (c) {
                is PatchChange.ReplaceResourceString -> if (c.resourceId.startsWith("$i/")) {
                    val e = entries.getValue(c.resourceId.substringAfter('/')); require(e.valueAsString == c.expectedOriginal); e.setValueAsString(c.replacement)
                }
                is PatchChange.ReplaceResourceColor -> if (c.resourceId.startsWith("$i/")) {
                    val e = entries.getValue(c.resourceId.substringAfter('/')); require(e.resValue.data.toLong().and(0xffffffffL) == c.expectedOriginalArgb); e.setValueAsRaw(ValueType.COLOR_ARGB8, c.replacementArgb.toInt())
                }
                else -> Unit
            }
            fun resourceSnapshot(apk: ApkAccess) = ResourceAnalyzer.entries(apk.module).associate { ResourceAnalyzer.key(it) to it.toJson().toString() }
            require(resourceSnapshot(old) == resourceSnapshot(new)) { "Unexpected resource changes" }
            require(old.module.hasTableBlock() == new.module.hasTableBlock())
            if (old.module.hasTableBlock()) require(old.module.tableBlock.toJson().toString() == new.module.tableBlock.toJson().toString()) { "Unexpected resource table metadata" }
            val layouts = spec.changes.filterIsInstance<PatchChange.SetLayoutAttribute>().filter { it.resourceId.startsWith("$i/") }.groupBy { it.resourceId.substringAfter('/').substringBefore('#') }
            for ((path, edits) in layouts) {
                val doc = old.module.loadResXmlDocument(path)
                for (c in edits) {
                    val id = c.resourceId.substringAfter('#').toLong(16).toInt()
                    val e = doc.recursiveElements().asSequence().map { it as com.reandroid.arsc.chunk.xml.ResXmlElement }.single { e -> e.attributes.asSequence().any { it.nameId == 0x010100d0 && it.data == id } }
                    val attr = e.getOrCreateAndroidAttribute(c.attribute, when(c.attribute) { "visibility" -> 0x010100dc; "contentDescription" -> 0x01010273; else -> error("Unknown attribute") })
                    if (c.attribute == "visibility") { require((attr.data * 4).toString() == c.expectedOriginal); attr.valueType = ValueType.DEC; attr.data = c.replacement.toInt() / 4 }
                    else { require(attr.valueString == c.expectedOriginal); attr.setValueAsString(c.replacement) }
                }
                require(doc.serializeToXml() == new.module.loadResXmlDocument(path).serializeToXml()) { "Unexpected layout changes" }
            }
            if (clone != null) {
                val manifest = old.module.androidManifest; val pkg = manifest.packageName
                require(pkg in setOf("com.mcpintelligence.fr3k.patchfixture", "com.mcpintelligence.fr3k.patchfixturetwo") && clone == "$pkg.hudmod")
                for (tag in listOf("activity", "activity-alias", "service", "receiver")) for (e in manifest.listApplicationElementsByTag(tag)) {
                    val attr = e.getOrCreateAndroidAttribute("name", 0x01010003)
                    if (attr.valueString.orEmpty().startsWith('.')) attr.setValueAsString(pkg + attr.valueString)
                }
                manifest.applicationClassName?.takeIf { it.startsWith('.') }?.let { manifest.setApplicationClassName(pkg + it) }
                manifest.packageName = clone
            }
            require(old.module.androidManifest.serializeToXml() == new.module.androidManifest.serializeToXml()) { "Unexpected manifest changes" }
            val semanticEntries = oldDex.keys + listOfNotNull(helperName) + layouts.keys + setOf("resources.arsc", "AndroidManifest.xml")
            val patched = new.sources.associateBy { it.alias }
            for (entry in old.sources) {
                if (entry.alias in semanticEntries || signature(entry.alias)) continue
                val other = patched.getValue(entry.alias)
                require(entry.openStream().use { it.readBytes() }.contentEquals(other.openStream().use { it.readBytes() })) { "Unexpected file change: ${entry.alias}" }
            }
        }
        return JSONObject().put("status", "STATIC_READBACK_PASS").put("apkCount", after.size).put("changes", spec.changes.size).put("runtime", "NOT_TESTED")
    }
}
