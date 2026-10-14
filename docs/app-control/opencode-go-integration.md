# OpenCode Go integration — the header contract

The provider is **OpenCode Go** (`https://opencode.ai/zen/go/v1`), the paid subscription for open coding
models. Earlier builds of this app pointed at the zero-key `/zen/v1` path and sent neither the right
endpoint nor the routing header the API now requires. Both are fixed and both are asserted by tests.

## What the API requires

| Requirement | Value | Verified by |
|---|---|---|
| Base URL | `https://opencode.ai/zen/go/v1` | `OpenCodeGoProviderTest.theDefaultEndpointIsGoNotTheZeroKeyPath` |
| Auth | `Authorization: Bearer <your key>` | live device test + `theSessionRequestCarriesBothHeadersGoRequires` |
| Routing | `x-opencode-session: <stable id>` | live device test + the same unit test |
| Chat path | `POST /chat/completions` | live device test |
| Catalogue | `GET /models` (key only, no routing header needed) | live device test: `40 models cached` |

Without `x-opencode-session` the API answers:

    HTTP 400 {"type":"error","error":{"type":"MissingSessionID",
              "message":"Request is missing x-opencode-session and cannot be routed efficiently."}}

The provider now surfaces that body verbatim (truncated, credential-redacted), because a swallowed reason
made this bug invisible for a whole build.

## Which Go models can drive the app's chat

Only the models served on the OpenAI-compatible `/chat/completions` shape can carry native tool calls,
and tool calls are what the shared agent session — the chat bubble and the mini browser — depends on. The
app offers exactly those:

    mimo-v2.6-pro, mimo-v2.6-flash, mimo-v2.5-pro, mimo-v2.5, deepseek-v4-flash-vision-exp

Go also lists models on `/responses` (grok-4.7, gpt-6-luna, muse-spark-*) and `/messages` (minimax-m3,
minimax-m2.7). Those need a different client; offering them would produce failures that look like
authentication bugs, so they are deliberately absent. Default is `mimo-v2.6-pro`.

## Precedence, app-wide

`AiProviderPreference.resolve` is the single decision point used by the chat bubble, the mini browser, the
shared agent session and the model picker:

1. An explicit choice in Settings (`OPENCODE` / `OPENROUTER`) wins outright.
2. Otherwise, a stored **OpenCode key replaces OpenRouter everywhere**, even when an OpenRouter key is
   also stored.
3. Otherwise an OpenRouter key is used.
4. With no keys at all, OpenCode Go is used — it is the only configured-by-default path that can answer.

The resolution is evaluated per call, not cached, so saving or removing a key takes effect immediately
without restarting the app.

## Live validation (real key, real endpoint)

The key is injected by the host into the app's private storage and never appears in a command line, a
repository file or a log:

    python3 - <<'PY'
    import subprocess
    key = "<your OpenCode Go key>"
    pkg, target = 'com.mcpintelligence.fr3k.hud', '/data/user/0/com.mcpintelligence.fr3k.hud/files/opencode-go-key.txt'
    subprocess.run(['adb','-s','<serial>','shell','run-as',pkg,'tee',target], input=key, text=True)
    PY
    adb -s <serial> shell am instrument -w \
      -e class com.mcpintelligence.fr3k.integrations.opencode.OpenCodeGoLiveDeviceTest \
      com.mcpintelligence.fr3k.hud.test/androidx.test.runner.AndroidJUnitRunner

The test moves the key into SecureStore, asserts the endpoint and the credential the provider actually
sends, probes the candidate models, performs a real tool-calling round trip, persists the model that
answered, and writes a receipt to `files/opencode-go-live-receipt.json`. It deletes the injected file when
the class finishes and skips (never fails) when no key is present, so a clean device still runs the suite.

Observed receipt on an API 35 emulator, no companion apps installed:

    endpoint  https://opencode.ai/zen/go/v1
    candidates [ {model: mimo-v2.6-pro, ok: true, elapsedMs: 3339, hasContent: true} ]
    winningModel mimo-v2.6-pro
    toolRoundTrip {attempted: true, ok: true, toolCallCount: 1, firstTool: app__control__inspect}

That last line is the important one: the model returned a real tool call named exactly as the shared
agent session names its tools, which is the capability the chat and browser sessions need.

## Honest limits

- A Go subscription is billed by OpenCode, not by this app. There is no free tier on Go; the app's
  zero-cost path is the public `/zen` bearer that Go does not use.
- The key lives in this installation's encrypted store and is sent only to OpenCode. It is never written
  to a log, a receipt or a repository file, and errors redact it.
- Rotate the key if it is ever pasted somewhere it should not be: it was shared in a chat session during
  development, and a key that has been displayed should be treated as compromised.
