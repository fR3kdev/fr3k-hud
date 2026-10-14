package com.mcpintelligence.fr3k.capability

import org.json.JSONArray
import org.json.JSONObject

data class MapElement(val label: String, val selector: SelectorFingerprint)

data class MapEdge(val from: String, val action: Step, val to: String)

data class AppMap(
    val identity: AppIdentity,
    val screens: List<MapElement2>,
    val edges: List<MapEdge>,
) {
    init {
        require(screens.isNotEmpty()) { "map needs at least one screen" }
        val names = screens.map { it.name }
        require(names.toSet().size == names.size) { "duplicate screen names" }
        require(edges.all { it.from in names && it.to in names }) { "edge references unknown screen" }
    }

    fun stale(current: AppIdentity): Boolean = current.fingerprint != identity.fingerprint

    fun toJson(): String = JSONObject().apply {
        put("schema", 1)
        put("identity", JSONObject().apply {
            put("packageName", identity.packageName)
            put("versionCode", identity.versionCode)
            put("signerSha256", identity.signerSha256)
            put("splitSha256", JSONArray(identity.splitSha256))
        })
        put("screens", JSONArray().also { arr ->
            screens.forEach { screen ->
                arr.put(JSONObject().apply {
                    put("name", screen.name)
                    put("reachVia", Step.listToJson(screen.reachVia))
                    put("elements", JSONArray().also { elements ->
                        screen.elements.forEach { element ->
                            elements.put(JSONObject().apply {
                                put("label", element.label)
                                put("selector", element.selector.toJson())
                            })
                        }
                    })
                })
            }
        })
        put("edges", JSONArray().also { arr ->
            edges.forEach { edge ->
                arr.put(JSONObject().apply {
                    put("from", edge.from)
                    put("to", edge.to)
                    put("action", edge.action.toJson())
                })
            }
        })
    }.toString(2)

    companion object {
        fun fromJson(json: String): AppMap {
            val root = JSONObject(json)
            require(root.getInt("schema") == 1) { "unsupported app map schema" }
            val identityJson = root.getJSONObject("identity")
            val splits = identityJson.getJSONArray("splitSha256")
            val identity = AppIdentity(
                identityJson.getString("packageName"),
                identityJson.getLong("versionCode"),
                identityJson.getString("signerSha256"),
                (0 until splits.length()).map { splits.getString(it) },
            )
            val screens = root.getJSONArray("screens").let { arr ->
                (0 until arr.length()).map { index ->
                    val obj = arr.getJSONObject(index)
                    MapElement2(
                        name = obj.getString("name"),
                        reachVia = Step.listFromJson(obj.getJSONArray("reachVia")),
                        elements = obj.getJSONArray("elements").let { els ->
                            (0 until els.length()).map { i ->
                                val e = els.getJSONObject(i)
                                MapElement(e.getString("label"), SelectorFingerprint.fromJson(e.getJSONObject("selector")))
                            }
                        },
                    )
                }
            }
            val edges = (0 until root.getJSONArray("edges").length()).map { index ->
                val obj = root.getJSONArray("edges").getJSONObject(index)
                MapEdge(obj.getString("from"), Step.fromJson(obj.getJSONObject("action")), obj.getString("to"))
            }
            return AppMap(identity, screens, edges)
        }
    }
}

/** One recorded screen: how to reach it, and what is on it. */
data class MapElement2(
    val name: String,
    val reachVia: List<Step>,
    val elements: List<MapElement>,
)
