package com.mcpintelligence.fr3k.hud.overlays

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Opt-in speech for the visible chat bubble, using the Android system engine. */
object TtsPreference {
    private const val PREFS = "fr3k_hud_prefs"
    private const val KEY = "tts_enabled"
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var generation = 0

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }

    fun speakIfEnabled(ctx: Context, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val app = ctx.applicationContext
        main.post {
            if (!isEnabled(app)) return@post
            if (ready) {
                engine?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "fr3k-chat")
            } else {
                pending = clean
                if (engine == null) {
                    val token = ++generation
                    engine = TextToSpeech(app) { status ->
                        main.post {
                            if (token != generation) return@post
                            if (status == TextToSpeech.SUCCESS) {
                                ready = true
                                engine?.language = Locale.getDefault()
                                val reply = pending
                                pending = null
                                if (reply != null && isEnabled(app))
                                    engine?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "fr3k-chat")
                            } else {
                                pending = null
                                engine?.shutdown()
                                engine = null
                            }
                        }
                    }
                }
            }
        }
    }

    fun shutdown() {
        main.post {
            generation++
            pending = null
            ready = false
            engine?.stop()
            engine?.shutdown()
            engine = null
        }
    }
}
