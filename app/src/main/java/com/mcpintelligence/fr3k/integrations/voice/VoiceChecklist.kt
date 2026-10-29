package com.mcpintelligence.fr3k.integrations.voice

/**
 * What still has to be true before a voice conversation can work, in the order it should be fixed.
 *
 * Pure logic so it can be tested without Android. The UI renders [Step]s and speaks [Step.spoken];
 * the first missing step is the one the walkthrough starts with.
 */
object VoiceChecklist {

    data class Device(val microphone: Boolean, val tts: Boolean, val recogniser: Boolean, val provider: Boolean)

    enum class Fix { MICROPHONE, TTS_SETTINGS, SPEECH_INPUT, PROVIDER }

    data class Step(val fix: Fix, val title: String, val detail: String, val spoken: String)

    fun missing(device: Device): List<Step> = buildList {
        if (!device.microphone) add(
            Step(
                Fix.MICROPHONE,
                "Microphone permission",
                "FR3K needs the microphone to hear you.",
                "First, allow the microphone. Tap allow microphone, then choose Allow.",
            )
        )
        if (!device.tts) add(
            Step(
                Fix.TTS_SETTINGS,
                "Android text-to-speech",
                "No speech engine or no language data is installed, so the assistant cannot speak.",
                "Next, text to speech is not ready. Open Android voice settings, install a speech engine and download your language.",
            )
        )
        if (!device.recogniser) add(
            Step(
                Fix.SPEECH_INPUT,
                "Speech recognition",
                "No speech recognition service is installed, so FR3K cannot hear words.",
                "Next, speech recognition is missing. Open speech input settings and install the Google speech service.",
            )
        )
        if (!device.provider) add(
            Step(
                Fix.PROVIDER,
                "HUD assistant provider",
                "No model provider key is configured, so there is nothing to answer with.",
                "Finally, add a model provider key in the HUD settings, under providers.",
            )
        )
    }

    /** One spoken sentence covering everything that is missing, or null when nothing is. */
    fun walkthrough(device: Device): String? {
        val steps = missing(device)
        if (steps.isEmpty()) return null
        return steps.map { it.spoken }.joinToString(" ")
    }

    fun ready(device: Device): Boolean = missing(device).isEmpty()
}
