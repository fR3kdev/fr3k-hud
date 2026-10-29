package com.mcpintelligence.fr3k.voice

/**
 * Wire contract for the HUD-hosted voice assistant.
 *
 * The HUD owns the voice engine (Android TTS, and the shared agent session that produces the
 * answer). Other FR3K apps bind [ACTION] and ask it to speak or to answer a spoken question, so
 * one voice implementation serves the whole app family instead of each app re-implementing it.
 *
 * Protected by a signature-level permission: only apps signed with the same key may bind.
 */
object VoiceAssistProtocol {
    const val ACTION = "com.mcpintelligence.fr3k.voice.VOICE_ASSIST"
    const val PERMISSION = "com.mcpintelligence.fr3k.hud.permission.VOICE_ASSIST"

    /** Client → HUD. */
    const val MSG_ASK = 1
    const val MSG_SPEAK = 2
    const val MSG_STOP = 3
    const val MSG_STATUS = 4

    /** HUD → client, always sent to `message.replyTo`. */
    const val MSG_RESULT = 100

    /** v2: RESULT carries answer text; SPEECH_DONE is emitted only by TTS onDone. */
    const val MSG_SPEECH_DONE = 101
    const val KEY_VERSION = "version"
    const val VERSION = 2
    class SpeechCompletion {
        private var current: String? = null
        fun begin(id: String) { current = id }
        fun cancel() { current = null }
        fun finish(id: String): Boolean {
            if (current != id) return false
            current = null
            return true
        }
    }

    const val KEY_PROMPT = "prompt"
    const val KEY_TEXT = "text"
    const val KEY_REPLY = "reply"
    const val KEY_ERROR = "error"
    const val KEY_REQUEST_ID = "requestId"
    /** Which app's briefing the answer should use: "hud" or "blackwave". */
    const val KEY_CONTEXT = "context"
    /** Status reply: whether the assistant can actually answer (provider + speech). */
    const val KEY_READY = "ready"
    const val KEY_DETAIL = "detail"

    /** Longest accepted prompt or spoken text. Bounded so a client cannot flood the engine. */
    const val MAX_TEXT = 2000

    sealed class Request {
        data class Ask(val prompt: String) : Request()
        data class Speak(val text: String) : Request()
        object Stop : Request()
        object Invalid : Request()
    }

    /** Rejects blank, over-long and control-character payloads; never trims silently into a no-op. */
    fun clean(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.length > MAX_TEXT) return null
        if (trimmed.any { it.isISOControl() && it != '\n' && it != '\t' }) return null
        return trimmed
    }

    fun decode(what: Int, prompt: String?, text: String?): Request = when (what) {
        MSG_ASK -> clean(prompt)?.let { Request.Ask(it) } ?: Request.Invalid
        MSG_SPEAK -> clean(text)?.let { Request.Speak(it) } ?: Request.Invalid
        MSG_STOP -> Request.Stop
        else -> Request.Invalid
    }
}
