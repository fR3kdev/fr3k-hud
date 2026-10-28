package com.mcpintelligence.fr3k.voice

import android.content.Context

/** Reply voice tuning: speaking rate and pitch, persisted per device. */
object VoiceTuning {
    private const val PREFS = "fr3k_hud_prefs"
    private const val KEY_RATE = "tts_rate"
    private const val KEY_PITCH = "tts_pitch"
    private const val KEY_TUNED = "tts_tuned"

    const val MIN_RATE = 0.5f
    const val MAX_RATE = 2.0f
    const val MIN_PITCH = 0.5f
    const val MAX_PITCH = 2.0f

    fun rate(context: Context): Float = prefs(context).getFloat(KEY_RATE, 1.0f).coerceIn(MIN_RATE, MAX_RATE)
    fun pitch(context: Context): Float = prefs(context).getFloat(KEY_PITCH, 1.0f).coerceIn(MIN_PITCH, MAX_PITCH)

    /** True once the operator has actually chosen a voice/speed, so setup stops asking. */
    fun tuned(context: Context): Boolean = prefs(context).getBoolean(KEY_TUNED, false)

    fun save(context: Context, rate: Float, pitch: Float) {
        prefs(context).edit()
            .putFloat(KEY_RATE, rate.coerceIn(MIN_RATE, MAX_RATE))
            .putFloat(KEY_PITCH, pitch.coerceIn(MIN_PITCH, MAX_PITCH))
            .putBoolean(KEY_TUNED, true)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
