package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import com.mcpintelligence.fr3k.capability.*
import com.mcpintelligence.fr3k.bridge.BridgeGuard
import java.io.File

/** Recorded selectors remain bound to their original APKs; app upgrades never silently rebind taps. */
object UserAppMaps {
    private fun root(context: Context)=File(context.noBackupFilesDir,"app-control/maps").apply { mkdirs() }
    fun save(context:Context,map:AppMap) {
        val file=File(root(context),"${map.identity.fingerprint}.json")
        file.writeText(map.toJson())
    }
    fun descriptors(context:Context, installed:Map<String,AppIdentity>):List<CapabilityDescriptor> = root(context).listFiles().orEmpty().flatMap { file ->
        val map=runCatching { AppMap.fromJson(file.readText()) }.getOrNull() ?: return@flatMap emptyList()
        if(installed[map.identity.packageName]?.fingerprint!=map.identity.fingerprint) return@flatMap emptyList()
        val prefix="app.map.${map.identity.fingerprint.take(16)}"
        map.screens.flatMapIndexed { screenIndex,screen ->
            screen.elements.filter { !BridgeGuard.isDestructive(it.label) }.mapIndexedNotNull { index,element ->
                runCatching { CapabilityDescriptor("$prefix.s$screenIndex.e$index.click",Plane.BRIDGE,RiskClass.REVERSIBLE_WRITE,
                    "Press ${element.label} in ${map.identity.packageName}",map.identity,
                    screen.reachVia+listOf(Step.WaitFor(element.selector),Step.Click(element.selector),Step.Observe(400)),sourceBundle="user.appmap") }.getOrNull()
            }
        }
    }
}
