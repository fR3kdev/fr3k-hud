# Hermes bridge

Hermes is FR3K's primary AI endpoint. The bridge wraps every Hermes call as
an envelope envelope of type `agent.ask`.

## Endpoint

The default endpoint is `http://127.0.0.1:8082` (loopback — the phone's own
Termux-side Hermes server, standalone phone-local) and is configurable in
`AppSettings.hermesEndpoint`. The value is the envelope POST **root**: the
transport always sends `POST <root>/envelope` with exactly one `/` separator
(see `HttpsTransport.buildEnvelopeUrl`), so a trailing slash or an embedded
`/api/v1/agent` root is joined without producing `//envelope`. The user can
override to a LAN address, a custom domain, or a private Ollama-style server.
A legacy `https://hermes.local/api/v1/agent` default is migrated to the
loopback default on read.

## Authentication

A bearer token is stored in `SecureStore` under the key
`hermes.auth.token` (configurable via `AppSettings.hermesAuthTokenKey`).
Tokens are never logged.

## Flow

```
share → context → askAboutThis → HermesAskCommand
  ↓
HermesProvider.ask(request)
  ↓ builds envelope of type agent.ask
HttpsTransport.send(envelope)
  ↓
Hermes replies with envelope of type "result" / "agent.reply"
  ↓
AgentAskResponse parsed
  ↓
Rendered in AskAboutThisActivity / returned to CommandResult
```

## Local fallback

If Hermes is unreachable, the provider returns a structured
`AgentAskResponse` with a "saved locally" message. The UI still renders a useful
acknowledgement. When the endpoint becomes reachable again, the saved request
is retried (V3).

## Routing

`AiPolicy` selects the right provider based on:

- `profile` field of the request (PRIVATE → local only, RESEARCH → cloud, etc.)
- Currently available capabilities
- Provider health (`online`, `latencyMs`)

The UI is provider-agnostic. Adding a new AI provider is a single `AiProvider`
implementation.

## Profiles

| Profile | Behaviour |
|---------|-----------|
| `NORMAL` | Default — Hermes routing |
| `FAST` | Short replies |
| `PRIVATE` | Local AI only |
| `OFFLINE` | Local first, offline-tolerant |
| `RESEARCH` | Web tools allowed |
| `CODE` | Code-focused |
| `CHEAP` | Cheapest viable |