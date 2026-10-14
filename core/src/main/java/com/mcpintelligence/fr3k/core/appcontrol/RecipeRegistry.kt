package com.mcpintelligence.fr3k.core.appcontrol

/** Registry entries are supplied by trusted application code, never model tool arguments.
 * A digest is an identity binding, not cryptographic proof of recipe provenance by itself.
 */
data class Recipe(val id: String, val sha256: String, val target: TargetIdentity,
                  val supportsLiveToggle: Boolean) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_.-]{0,95}")))
        require(validDigest(sha256))
    }
}
class RecipeRegistry(recipes: List<Recipe> = emptyList()) {
    private val entries = recipes.toList()
    init { require(entries.map { it.id to it.target }.distinct().size == entries.size) }
    fun find(id: String, target: TargetIdentity): Recipe? =
        entries.singleOrNull { it.id == id && it.target == target }
}
