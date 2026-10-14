package com.mcpintelligence.fr3k.capability

import org.json.JSONArray
import org.json.JSONObject

data class CapabilityBundle(
    val bundleId: String,
    val version: String,
    val publisher: String,
    val capabilities: List<CapabilityDescriptor>,
    val rawSha256: String,
) {
    private val index: Map<String, List<CapabilityDescriptor>> = capabilities.groupBy { it.id }

    fun find(id: String, identity: AppIdentity): CapabilityDescriptor? =
        index[id]?.singleOrNull { it.identity.fingerprint == identity.fingerprint }

    fun listFor(identity: AppIdentity): List<CapabilityDescriptor> =
        capabilities.filter { it.identity.fingerprint == identity.fingerprint }
}

object BundleLoader {
    const val SUPPORTED_SCHEMA = 1

    fun parse(json: String, rawSha256: String = sha256Hex(json.toByteArray())): CapabilityBundle {
        val root = JSONObject(json)
        val schema = root.optInt("schema", -1)
        require(schema == SUPPORTED_SCHEMA) { "unsupported bundle schema $schema" }
        val bundleId = root.getString("bundleId")
        require(bundleId.matches(Regex("[a-z0-9][a-z0-9_.-]{1,63}"))) { "invalid bundleId" }
        val publisher = root.optString("publisher", "unknown")
        val arr = root.getJSONArray("capabilities")
        val descriptors = (0 until arr.length()).map { index ->
            val obj = arr.getJSONObject(index)
            val identityJson = obj.getJSONObject("identity")
            val splits = identityJson.getJSONArray("splitSha256")
            val identity = AppIdentity(
                packageName = identityJson.getString("packageName"),
                versionCode = identityJson.getLong("versionCode"),
                signerSha256 = identityJson.getString("signerSha256"),
                splits = (0 until splits.length()).map { splits.getString(it) },
            )
            val params = obj.optJSONObject("parameters")?.let { obj2 ->
                obj2.keys().asSequence().associateWith { ParamType.valueOf(obj2.getString(it).uppercase()) }
            } ?: emptyMap()
            CapabilityDescriptor(
                id = obj.getString("id"),
                plane = Plane.valueOf(obj.getString("plane").uppercase()),
                risk = RiskClass.valueOf(obj.getString("risk").uppercase()),
                summary = obj.getString("summary"),
                identity = identity,
                steps = Step.listFromJson(obj.getJSONArray("steps")),
                parameters = params,
                sourceBundle = bundleId,
            )
        }
        require(descriptors.isNotEmpty()) { "bundle contains no capabilities" }
        return CapabilityBundle(bundleId, root.getString("version"), publisher, descriptors, rawSha256)
    }

    fun toJson(bundle: CapabilityBundle): String = JSONObject().apply {
        put("schema", SUPPORTED_SCHEMA)
        put("bundleId", bundle.bundleId)
        put("version", bundle.version)
        put("publisher", bundle.publisher)
        put("capabilities", JSONArray().also { arr ->
            bundle.capabilities.forEach { descriptor ->
                arr.put(JSONObject().apply {
                    put("id", descriptor.id)
                    put("plane", descriptor.plane.name)
                    put("risk", descriptor.risk.name)
                    put("summary", descriptor.summary)
                    put("identity", JSONObject().apply {
                        put("packageName", descriptor.identity.packageName)
                        put("versionCode", descriptor.identity.versionCode)
                        put("signerSha256", descriptor.identity.signerSha256)
                        put("splitSha256", JSONArray(descriptor.identity.splitSha256))
                    })
                    put("parameters", JSONObject().also { p ->
                        descriptor.parameters.forEach { (k, v) -> p.put(k, v.name.lowercase()) }
                    })
                    put("steps", Step.listToJson(descriptor.steps))
                })
            }
        })
    }.toString(2)
}
