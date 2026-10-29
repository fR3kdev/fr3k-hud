# Shared voice across FR3K apps

The HUD owns voice. Other FR3K apps ask it to speak and to answer, so the whole app family uses one
speech engine and one assistant instead of each app growing its own.

## Who does what

| Concern | Owner | Why |
|---|---|---|
| Speech output (TTS) | HUD | One engine, one configured voice; playback is allowed in the background |
| Answer generation | HUD | The shared agent session holds the conversation, tools and providers |
| Microphone capture | The foreground app | Android restricts background mic use; the app in front legitimately holds it |

So the caller transcribes its own question (its own `SpeechRecognizer`), sends the transcript, and
the HUD answers and speaks.

## Contract

`app/src/main/java/com/mcpintelligence/fr3k/voice/VoiceAssistProtocol.kt`

```
action      com.mcpintelligence.fr3k.voice.VOICE_ASSIST
permission  com.mcpintelligence.fr3k.hud.permission.VOICE_ASSIST   (protectionLevel=signature)
```

Bound with `Messenger` (no AIDL file). Client → HUD:

| `what` | Payload | Meaning |
|---|---|---|
| `MSG_ASK` (1) | `prompt` | Run one agent turn, speak the answer, return its text |
| `MSG_SPEAK` (2) | `text` | Speak only |
| `MSG_STOP` (3) | — | Stop speaking |

HUD → client, sent to `message.replyTo`:

| `what` | Payload |
|---|---|
| `MSG_RESULT` (100) | `requestId`, plus `reply` or `error` |

Limits: text is capped at 2000 characters and control characters are rejected, so a client cannot
flood the engine or inject protocol bytes. Answers time out at 90 s (95 s on the client side) and a
busy assistant answers `Assistant is busy` rather than queueing.

## Security

- Signature permission: only apps signed with the HUD's key can bind. Verified locally — both debug
  APKs carry certificate SHA-256 `6ca9ca0a…`, so the grant applies.
- No credentials, tokens or audio cross the boundary. The service never logs prompts or replies.
- A release build must sign both apps with the same release key, or binding is refused with
  `State.DENIED` (shown, not silently degraded).

## BLACKWAVE companion

`app/src/main/java/com/fr3k/blackwave/voice/HudVoiceClient.kt` binds the service;
`VoiceHelp.kt` renders the "VOICE WALKTHROUGH" card in every setup step. States are honest:
`READY`, `UNAVAILABLE` (HUD missing), `DENIED` (signature mismatch), `UNBOUND` (connecting).

The microphone stays in the companion: `stopListening()` is used on release so a pending transcript
still arrives, and nothing records after release.

## Not verified

No device run. Binding, TTS playback and the transcript round trip have not been exercised on
hardware; the HUD not being installed, force-stopped, or signed differently are handled in code but
untested on device.
