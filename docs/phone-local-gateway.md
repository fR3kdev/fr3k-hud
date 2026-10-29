# Phone-local gateway (gateway inside Termux on the device)

The BLACKWAVE mobile gateway is the Python service `blackwave_fleet serve-mobile` (FastAPI + uvicorn,
TLS with a self-signed certificate the app pins by SPKI). This document runs it **on the phone itself**,
bound to loopback, and pairs the companion app to it.

`scripts/device/phone_gateway.sh` automates every step below.

## What this topology does and does not buy you

- **Does**: gives the companion a real pairing flow — a `request_id`, a `nonce`, a TLS pin and a revocable
  credential — with no host on the network.
- **Does not**: create a trust boundary. Both ends are the same device, so the pin proves only that the
  local service has not been swapped since pairing.
- **Does not**: provide fleet data. A phone-local gateway has no registry (`FleetRegistry`) and no catalog,
  so Fleet / Device / OTA panels stay empty. Only pairing, health and inbox-backed flows answer.

## Prerequisites

- Termux with `python` (`pkg install -y python`), on Android 8.0+ (companion `minSdk` 26)
- the gateway source on the phone. The repo is private, so push a tarball instead of cloning:

```sh
# on x1
cd /home/fr3k/BLACKWAVE-STANDALONE/work/blackwave
tar -czf /tmp/blackwave-gateway-src.tgz src/blackwave_fleet pyproject.toml
adb push /tmp/blackwave-gateway-src.tgz /sdcard/Download/
# in Termux
mkdir -p ~/blackwave && tar -xzf ~/storage/downloads/blackwave-gateway-src.tgz -C ~/blackwave
```

- the script itself, pushed the same way, then made executable:

```sh
adb push scripts/device/phone_gateway.sh /sdcard/Download/
# in Termux
install -m 700 ~/storage/downloads/phone_gateway.sh ~/phone_gateway.sh
```

## Run it

```sh
export BLACKWAVE_SRC=$HOME/blackwave

bash ~/phone_gateway.sh doctor    # interpreter, deps, source, listener
bash ~/phone_gateway.sh install   # pip: fastapi uvicorn cryptography pydantic pydantic-settings
bash ~/phone_gateway.sh start     # serve-mobile on https://127.0.0.1:8878
bash ~/phone_gateway.sh pair      # prints the pairing payload JSON (ttl 900s by default)
bash ~/phone_gateway.sh status
bash ~/phone_gateway.sh stop
```

Paste the `pair` output into the app: **BLACKWAVE → SET UP BLACKWAVE → PAIR GATEWAY**, or the HUD's
**Settings → blackwave pairing → pairing QR JSON → CHECK PAYLOAD** (both apply the same validation rules).

The app accepts `127.0.0.1` as a pairing host (`RiskPolicy.parse` allows `[A-Za-z0-9.-]+`), so the loopback
address in the payload is valid. Certificate pinning works the same as over the LAN because
`pairing.complete` re-checks the SPKI the server presents.

## Dependencies, precisely

`blackwave_fleet.cli` and `blackwave_fleet.mobile` import `fastapi`, `uvicorn`, `pydantic`, `cryptography`
and `starlette`. They do **not** import `keyring` — only `secrets.py` does, which is used for flashing
credentials, not pairing. So `pkg install -y python` plus the pip packages above is enough for this path;
add `keyring` only if you intend to store secrets on the phone.

`cryptography` needs an aarch64 wheel; Termux' pip usually finds one. If it builds from source, install
`pkg install -y rust binutils openssl` first (the script does this in `install`).

## Ports

- `8878` — the mobile gateway used here (`serve-mobile`)
- `8877` — the desktop bridge (`serve`), unrelated; do not confuse them

Loopback port binding needs no `adb reverse` and no firewall rule. If you want the x1 host gateway to be
reachable from the phone instead, reverse it: `adb reverse tcp:8878 tcp:8878` does **not** help for TLS
pinning unless the payload names a host whose certificate matches, so prefer the plain LAN address.

## Troubleshooting

- `ModuleNotFoundError: No module named 'uvicorn'` → `bash ~/phone_gateway.sh install`, then re-run; the
  repo `.venv` on x1 has the same problem (only Hermes' runtime venv carries uvicorn).
- `pairing QR must name a reachable gateway address` → `--advertised-host` cannot be `0.0.0.0`; the loopback
  value is correct here.
- `Gateway API version not supported` / `invalid gateway SPKI pin` → the payload came from a different
  store (`~/.config/fr3k-blackwave/mobile`) than the running server; mint both from the same `$BLACKWAVE_SRC`.
- request expired → `create_request` defaults to 180 s; the script passes 900 s. Single use, so mint again.
- Android kills the process when Termux is swiped away → run `termux-wake-lock` before `start`, and expect
  the app to show the gateway unreachable when Termux is gone; this is a paired-but-offline state, not a
  broken pairing.
