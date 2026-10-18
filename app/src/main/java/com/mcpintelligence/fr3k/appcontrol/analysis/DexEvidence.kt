package com.mcpintelligence.fr3k.appcontrol.analysis

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.mcpintelligence.fr3k.capability.appmod.*
import org.json.JSONObject
import java.io.File

object DexEvidence {
    fun methodKey(m: Method) = "${m.definingClass}->${m.name}(${m.parameterTypes.joinToString("")})${m.returnType}"
    fun extract(apk: File, packageName: String): List<FeatureEvidence> = ApkAccess.open(apk).use { extract(it, packageName) }
    fun extract(apk: ApkAccess, packageName: String): List<FeatureEvidence> {
        val out = ArrayList<FeatureEvidence>()
        val prefix = "L" + packageName.replace('.', '/') + "/"
        for ((dexName, dex) in apk.dexes()) for (cls in dex.classes) {
            if (!cls.type.startsWith(prefix)) continue
            for (m in cls.methods) {
                var offset = 0
                for (ins in (m.implementation?.instructions ?: emptyList())) {
                    if (out.size >= 400) return out
                    val literal = when {
                        ins.opcode in setOf(com.android.tools.smali.dexlib2.Opcode.CONST_STRING, com.android.tools.smali.dexlib2.Opcode.CONST_STRING_JUMBO) && ins is ReferenceInstruction -> "string:" + (ins.reference as StringReference).string
                        ins.opcode in setOf(com.android.tools.smali.dexlib2.Opcode.CONST_4, com.android.tools.smali.dexlib2.Opcode.CONST_16, com.android.tools.smali.dexlib2.Opcode.CONST) && ins is NarrowLiteralInstruction -> "int:${ins.narrowLiteral}"
                        else -> null
                    }
                    if (literal != null && literal.length <= 256) {
                        val key = "$dexName|${methodKey(m)}#$offset"
                        out += FeatureEvidence("dex:$key", EvidenceKind.OBSERVED,
                            JSONObject().put("literal", literal).put("opcode", ins.opcode.toString()).toString(), EvidenceReference.DexDescriptor(key, offset.toString()))
                    }
                    if (ins.opcode == com.android.tools.smali.dexlib2.Opcode.RETURN_OBJECT && m.returnType == "Ljava/lang/String;") {
                        val key = "$dexName|${methodKey(m)}#$offset"
                        val helpers = arrayListOf("url.clean")
                        if (m.parameterTypes.firstOrNull()?.toString() == "Landroid/content/Context;" && m.implementation!!.registerCount < 16) {
                            helpers += "hud.share"
                            if (m.parameterTypes.getOrNull(1)?.toString() == "Ljava/lang/String;") helpers += "preference.persist"
                        }
                        out += FeatureEvidence("helper:$key", EvidenceKind.OBSERVED, JSONObject().put("helpers", org.json.JSONArray(helpers)).toString(), EvidenceReference.DexDescriptor(key, offset.toString()))
                    }
                    offset += ins.codeUnits
                }
            }
        }
        return out
    }
}
