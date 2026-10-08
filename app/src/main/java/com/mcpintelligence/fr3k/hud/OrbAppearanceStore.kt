package com.mcpintelligence.fr3k.hud

import android.content.Context

/**
 * Persisted appearance of the floating HUD orb.
 *
 * The orb can be:
 *  - [Mode.BUILTIN] — the FR3K glyph (default),
 *  - [Mode.IMAGE]   — a static image chosen by the user,
 *  - [Mode.GIF]     — an animated GIF chosen by the user.
 *
 * [sizeDp] lets the user shrink or grow the orb; it stays small by design so the
 * overlay occupies minimal screen space while collapsed.
 */
data class OrbAppearance(
    val mode: Mode = Mode.BUILTIN,
    val imageUri: String? = null,
    val sizeDp: Int = 48,
) {
    enum class Mode { BUILTIN, IMAGE, GIF }

    /** True when a user image/GIF should be rendered instead of the built-in glyph. */
    val hasCustomImage: Boolean
        get() = mode != Mode.BUILTIN && !imageUri.isNullOrBlank()

    /** Coerced to a sane on-screen range (24dp…128dp). */
    val clampedSizeDp: Int
        get() = sizeDp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)

    companion object {
        const val MIN_SIZE_DP = 24
        const val MAX_SIZE_DP = 128
    }
}

/** SharedPreferences-backed store for [OrbAppearance]; deliberately simple and synchronous. */
class OrbAppearanceStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(): OrbAppearance {
        val mode = runCatching {
            OrbAppearance.Mode.valueOf(prefs.getString(KEY_MODE, OrbAppearance.Mode.BUILTIN.name)!!)
        }.getOrDefault(OrbAppearance.Mode.BUILTIN)
        return OrbAppearance(
            mode = mode,
            imageUri = prefs.getString(KEY_URI, null),
            sizeDp = prefs.getInt(KEY_SIZE, 48),
        )
    }

    fun write(appearance: OrbAppearance) {
        prefs.edit()
            .putString(KEY_MODE, appearance.mode.name)
            .putString(KEY_URI, appearance.imageUri)
            .putInt(KEY_SIZE, appearance.clampedSizeDp)
            .apply()
    }

    /** Revert to the built-in glyph while keeping the chosen size. */
    fun resetImage() {
        write(read().copy(mode = OrbAppearance.Mode.BUILTIN, imageUri = null))
    }

    private companion object {
        const val PREFS = "fr3k_orb_appearance"
        const val KEY_MODE = "mode"
        const val KEY_URI = "imageUri"
        const val KEY_SIZE = "sizeDp"
    }
}
