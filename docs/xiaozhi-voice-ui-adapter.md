# Xiaozhi voice UI contract

The adapter is wired. `VoiceAssistantPanel` (Settings → voice assistant) renders
`XiaozhiVoiceControls` with `realXiaozhiVoiceAdapter(context)`, a real
`XiaozhiVoiceAdapterImpl` over `XiaozhiController` + `XiaozhiAudio` + Android TTS.

## Interface (`ui/settings/XiaozhiVoice.kt`)

```kotlin
internal interface XiaozhiVoiceAdapter {
    val status: StateFlow<VoiceStatus>       // phase, message, activation code
    val speaksWithAndroidTts: Boolean        // true = Android TTS reads reply text
    fun setAndroidTtsOutput(enabled: Boolean)
    fun connect()                            // explicit user action only
    fun startTalking()                       // push-to-talk press
    fun stopTalking()                        // release: stops mic, keeps playback
    fun disconnect()                         // end channel, adapter stays reusable
    fun close()                              // tear down and release the engine
}
```

All members are main-thread only. `status` phases: `IDLE`, `CONNECTING`,
`ACTIVATION_PENDING`, `READY`, `LISTENING`, `SPEAKING`, `ERROR`.

## Rules the implementation enforces

- Consent is per screen visit. Nothing is contacted until the operator ticks consent and presses
  CONNECT; `discover(true)` is the only network entry point.
- Microphone permission is checked at press time. Granting it never starts capture — the operator
  must press again.
- `stopTalking()` stops capture and the protocol listen-start; it must not stop playback, or the
  reply is lost.
- Exactly one reply path is active: Android TTS speaks `tts`/`sentence_start` text, or the server's
  Opus audio plays. `XiaozhiAudio` keeps capture and playback on separate lifetimes so either
  ordering works.
- `disconnect()` leaves the adapter reusable for a later connect; `close()` releases TTS too.
- No activation codes, tokens, or audio are logged.

## Output modes

Default is Android TTS (`speaksWithAndroidTts = true`), matching the copied reference client.
The Reply voice row switches to `XIAOZHI`, which plays server audio and silences text speech.

## Tests

`app/src/test/.../XiaozhiVoiceAdapterTest.kt` drives the state machine with fake controller, audio
and speaker seams: consent gating, activation code, retry, negotiated rate, PTT release keeping
playback, mic failure, both output modes, frame forwarding, disconnect/reconnect, close.

## Still unverified

Live microphone capture, network Opus round trip and speaker playback have not run on hardware.
Enrollment is not completed; the bootstrap contract itself is confirmed against
`https://voice.fr3k.dev/xiaozhi/ota/` (HTTP 200, activation code + `wss` websocket block).
