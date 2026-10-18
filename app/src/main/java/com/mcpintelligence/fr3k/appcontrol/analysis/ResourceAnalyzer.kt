package com.mcpintelligence.fr3k.appcontrol.analysis

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ValueType
import com.mcpintelligence.fr3k.capability.appmod.*
import org.json.JSONObject
import java.io.File

object ResourceAnalyzer {
    fun key(entry: Entry): String = "%08x@%s".format(java.util.Locale.ROOT, entry.resourceId, entry.resConfig.qualifiers)
    fun entries(module: ApkModule): Sequence<Entry> = sequence {
        if (module.hasTableBlock()) for (resource in module.tableBlock.resources) for (entry in resource) yield(entry)
    }
    fun extract(apk: File): List<FeatureEvidence> = ApkAccess.open(apk).use { extract(it.module) }
    fun extract(module: ApkModule): List<FeatureEvidence> {
        val values = entries(module).filter { it.typeName in setOf("string", "color") && (it.valueType == ValueType.STRING || it.valueType.isColor) }.take(2000).map { e ->
            val value = if (e.valueType == ValueType.STRING) e.valueAsString else e.resValue.data.toLong().and(0xffffffffL).toString()
            FeatureEvidence("resource:${key(e)}", EvidenceKind.OBSERVED,
                JSONObject().put("type", e.typeName).put("name", e.name).put("value", value).put("qualifier", e.resConfig.qualifiers).toString(),
                EvidenceReference.Resource(key(e)))
        }.toList().toMutableList()
        for (layout in entries(module).filter { it.typeName == "layout" }.take(50)) {
            val path = layout.valueAsString ?: continue
            if (!path.matches(Regex("res/layout[-a-z0-9]*/[a-z0-9_]+\\.xml"))) continue
            val doc = module.loadResXmlDocument(path)
            for (raw in doc.recursiveElements().asSequence().take(200)) {
                val element = raw as com.reandroid.arsc.chunk.xml.ResXmlElement
                val attrs = element.attributes.asSequence().toList()
                val id = attrs.singleOrNull { it.nameId == 0x010100d0 }?.data ?: continue
                val visibility = attrs.singleOrNull { it.nameId == 0x010100dc }
                if (visibility != null && visibility.valueType !in setOf(ValueType.DEC, ValueType.HEX)) continue
                val visible = when (visibility?.data ?: 0) { 0 -> 0; 1 -> 4; 2 -> 8; else -> continue }
                val target = "$path#%08x".format(java.util.Locale.ROOT, id)
                values += FeatureEvidence("layout:$target", EvidenceKind.OBSERVED,
                    JSONObject().put("type", "layout").put("name", "$path/${id.toUInt()}").put("attribute", "visibility").put("value", visible.toString()).toString(), EvidenceReference.Resource(target))
            }
        }
        return values
    }
}
