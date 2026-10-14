package com.mcpintelligence.fr3k.capability

data class RegistryEntry(
    val descriptor: CapabilityDescriptor,
    val bundleId: String,
    val rawSha256: String,
    val verdict: SignerVerdict,
)

/** Local-first capability registry. Rejects duplicates for the same capability and identity. */
class RegistryStore(entries: List<RegistryEntry> = emptyList()) {
    private val byKey = LinkedHashMap<String, RegistryEntry>()

    init {
        entries.forEach { put(it) }
    }

    private fun key(id: String, fingerprint: String) = "$id@$fingerprint"

    fun put(entry: RegistryEntry) {
        require(entry.verdict.accepted) { "refusing to register a rejected bundle: ${entry.verdict.reason}" }
        val k = key(entry.descriptor.id, entry.descriptor.fingerprint)
        require(!byKey.containsKey(k)) { "duplicate capability ${entry.descriptor.id} for ${entry.descriptor.identity.label}" }
        byKey[k] = entry
    }

    fun find(id: String, identity: AppIdentity): CapabilityDescriptor? =
        byKey[key(id, identity.fingerprint)]?.descriptor

    fun listFor(identity: AppIdentity): List<CapabilityDescriptor> =
        byKey.values.map { it.descriptor }.filter { it.identity.fingerprint == identity.fingerprint }

    fun all(): List<RegistryEntry> = byKey.values.toList()

    fun ids(): List<String> = byKey.values.map { it.descriptor.id }.distinct().sorted()

    val size: Int get() = byKey.size

    companion object {
        fun fromJsonBundles(
            documents: List<Pair<String, String?>>,
            pinnedKeys: Map<String, String> = emptyMap(),
            allowUnsignedLocal: Boolean = true,
        ): RegistryStore {
            val store = RegistryStore()
            documents.forEach { (json, signature) ->
                val raw = sha256Hex(json.toByteArray())
                val probe = BundleLoader.parse(json, raw)
                val verdict = BundleSigner.vet(probe.publisher, json.toByteArray(), signature, pinnedKeys, allowUnsignedLocal)
                if (verdict.rejected) return@forEach
                probe.capabilities.forEach { store.put(RegistryEntry(it, probe.bundleId, raw, verdict)) }
            }
            return store
        }
    }
}
