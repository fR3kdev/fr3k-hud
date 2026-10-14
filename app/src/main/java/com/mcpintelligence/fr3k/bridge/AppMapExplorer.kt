package com.mcpintelligence.fr3k.bridge

import android.os.SystemClock
import com.mcpintelligence.fr3k.capability.AppIdentity
import com.mcpintelligence.fr3k.capability.AppMap
import com.mcpintelligence.fr3k.capability.MapEdge
import com.mcpintelligence.fr3k.capability.MapElement
import com.mcpintelligence.fr3k.capability.MapElement2
import com.mcpintelligence.fr3k.capability.SelectorFingerprint
import com.mcpintelligence.fr3k.capability.Step
import com.mcpintelligence.fr3k.capability.UiNode
import kotlinx.coroutines.delay

/**
 * Records what an app looks like from the outside, using deeplinks as the primary transitions
 * (arXiv 2609.30887) and bounded tapping as the fallback. Output is a version-bound app map.
 */
class AppMapExplorer(private val service: Fr3kBridgeService) {

    data class Result(val map: AppMap, val notes: List<String>)

    suspend fun explore(
        identity: AppIdentity,
        entries: List<Pair<String, String>>,
        maxClickProbesPerScreen: Int = 1,
    ): Result {
        require(entries.isNotEmpty()) { "exploration needs at least one entry point" }
        val screens = ArrayList<MapElement2>()
        val edges = ArrayList<MapEdge>()
        val notes = ArrayList<String>()

        entries.forEach { (name, deeplink) ->
            service.launch(launchIntent(deeplink))
            val tree = awaitTree(identity.packageName, 4000)
            if (tree == null) {
                notes.add("$name: no window from ${identity.packageName} within timeout")
                return@forEach
            }
            screens.add(
                MapElement2(
                    name = name,
                    reachVia = listOf(Step.Launch(deeplink)),
                    elements = describeElements(tree),
                ),
            )
        }

        // One bounded probe per recorded screen, always returning with BACK so exploration stays reversible.
        if (maxClickProbesPerScreen > 0) {
            screens.toList().forEach { screen ->
                val candidates = screen.elements.filter { !BridgeGuard.isDestructive(it.label) }.take(maxClickProbesPerScreen)
                candidates.forEach { candidate ->
                    val launch = screen.reachVia.firstOrNull() as? Step.Launch ?: return@forEach
                    service.launch(launchIntent(launch.target))
                    val tree = awaitTree(identity.packageName, 3000) ?: return@forEach
                    val executor = BridgeStepExecutor(service, setOf(identity.packageName), { _, _, _ -> "" })
                    runCatching { executor.execute(listOf(Step.Click(candidate.selector), Step.Observe(400))) }
                        .onFailure { notes.add("probe on ${screen.name} failed: ${it.message}") }
                    service.back()
                    delay(400)
                }
            }
        }
        return Result(AppMap(identity, screens, edges), notes)
    }

    private fun launchIntent(target: String): android.content.Intent =
        (if (target.contains("://")) android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(target))
        else service.packageManager.getLaunchIntentForPackage(target) ?: error("No launch activity for $target"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun describeElements(tree: UiNode): List<MapElement> {
        val out = ArrayList<MapElement>()
        fun walk(node: UiNode) {
            if (node.clickable && !node.password && out.size < 8) {
                val viewId = node.viewId
                val description = node.contentDescription
                val text = node.text
                val label = text ?: description ?: node.className ?: "element"
                val fingerprint = when {
                    viewId != null -> SelectorFingerprint(viewId = viewId)
                    description != null -> SelectorFingerprint(className = node.className, contentDescRegex = Regex.escape(description))
                    else -> SelectorFingerprint(className = node.className, textRegex = Regex.escape(text ?: ""))
                }
                out.add(MapElement(label, fingerprint))
            }
            node.children.forEach { walk(it) }
        }
        walk(tree)
        return out
    }

    private suspend fun awaitTree(packageName: String, timeoutMs: Long): UiNode? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (service.foregroundPackage() == packageName) {
                service.readTree()?.let { return it }
            }
            delay(150)
        }
        return null
    }
}
