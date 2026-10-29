#!/data/data/com.termux/files/usr/bin/bash
# Phone-local BLACKWAVE mobile gateway, for Termux on the device itself.
#
# Runs `blackwave_fleet serve-mobile` bound to loopback and mints a pairing payload that the
# companion app can paste into SET UP BLACKWAVE -> PAIR GATEWAY.
#
# Usage (inside Termux):
#   bash phone_gateway.sh doctor      # report interpreter + deps + source, change nothing
#   bash phone_gateway.sh install     # pkg/pip install the runtime deps
#   bash phone_gateway.sh start       # start the gateway on 127.0.0.1:8878 (background)
#   bash phone_gateway.sh pair        # mint a payload (default ttl 900s) and print the JSON
#   bash phone_gateway.sh status      # is it listening, and on what
#   bash phone_gateway.sh stop        # stop it
#
# Environment: BLACKWAVE_SRC (source tree), BLACKWAVE_PORT (default 8878), PAIR_TTL (default 900).
set -u

SRC="${BLACKWAVE_SRC:-$HOME/blackwave}"
PORT="${BLACKWAVE_PORT:-8878}"
TTL="${PAIR_TTL:-900}"
HOST=127.0.0.1
LOGF="$HOME/.blackwave-gateway.log"
PIDF="$HOME/.blackwave-gateway.pid"

need() { command -v "$1" >/dev/null 2>&1; }

py() {
  if need python3; then echo python3; else echo ""; fi
}

cmd_doctor() {
  echo "termux prefix : ${PREFIX:-unset}"
  echo "python        : $(py || true)"
  local p; p="$(py)"
  if [ -z "$p" ]; then
    echo "  MISSING python3 -> run: pkg install -y python"
  else
    "$p" - <<'PY'
import importlib, sys
print("  version      :", sys.version.split()[0])
for mod in ("fastapi", "uvicorn", "cryptography", "pydantic", "pydantic_settings"):
    try:
        m = importlib.import_module(mod)
        print(f"  {mod:<15}: {getattr(m, '__version__', 'ok')}")
    except Exception as exc:
        print(f"  {mod:<15}: MISSING ({exc.__class__.__name__})")
PY
  fi
  if [ -d "$SRC/src/blackwave_fleet" ]; then
    echo "source        : $SRC (blackwave_fleet present)"
  else
    echo "source        : $SRC -> MISSING src/blackwave_fleet (set BLACKWAVE_SRC)"
  fi
  echo "listening     : $(cmd_status_raw)"
}

cmd_install() {
  need python3 || pkg install -y python
  pkg install -y openssl libffi rust binutils 2>/dev/null || true
  python3 -m pip install --upgrade pip
  # cryptography ships an aarch64 wheel; if it falls back to a source build the rust toolchain above is used.
  python3 -m pip install fastapi uvicorn cryptography pydantic pydantic-settings
}

cmd_status_raw() {
  if [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF" 2>/dev/null)" 2>/dev/null; then
    echo "running (pid $(cat "$PIDF")) on https://$HOST:$PORT"
  else
    echo "not running"
  fi
}

cmd_start() {
  if [ ! -d "$SRC/src/blackwave_fleet" ]; then
    echo "error: no source at $SRC — set BLACKWAVE_SRC or copy the tree there" >&2
    exit 1
  fi
  if [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF" 2>/dev/null)" 2>/dev/null; then
    echo "already running (pid $(cat "$PIDF"))"
    return 0
  fi
  ( cd "$SRC" && PYTHONPATH="$SRC/src" nohup python3 -m blackwave_fleet.cli serve-mobile \
      --advertised-host "$HOST" --port "$PORT" >>"$LOGF" 2>&1 & echo $! >"$PIDF" )
  sleep 4
  echo "started: $(cmd_status_raw)"
  echo "log: $LOGF"
  tail -5 "$LOGF" 2>/dev/null
}

cmd_stop() {
  if [ -f "$PIDF" ]; then
    kill "$(cat "$PIDF")" 2>/dev/null || true
    rm -f "$PIDF"
  fi
  # the uvicorn worker may outlive the shell pid
  pkill -f "blackwave_fleet.cli serve-mobile" 2>/dev/null || true
  echo "stopped"
}

cmd_pair() {
  if [ ! -d "$SRC/src/blackwave_fleet" ]; then
    echo "error: no source at $SRC" >&2
    exit 1
  fi
  ( cd "$SRC" && PYTHONPATH="$SRC/src" PAIR_HOST="$HOST" PAIR_PORT="$PORT" PAIR_TTL="$TTL" python3 - <<'PY'
import os
from blackwave_fleet.pairing import PairingStore
from blackwave_fleet.mobile import DEFAULT_STORE
payload = PairingStore(DEFAULT_STORE).create_request(
    os.environ["PAIR_HOST"], int(os.environ["PAIR_PORT"]), ttl_seconds=int(os.environ["PAIR_TTL"])
)
print(payload.model_dump_json())
PY
  )
  echo "# paste that JSON into BLACKWAVE -> SET UP BLACKWAVE -> PAIR GATEWAY (valid ${TTL}s)" >&2
}

case "${1:-}" in
  doctor)  cmd_doctor ;;
  install) cmd_install ;;
  start)   cmd_start ;;
  pair)    cmd_pair ;;
  status)  cmd_status_raw ;;
  stop)    cmd_stop ;;
  *) echo "usage: $0 {doctor|install|start|pair|status|stop}" >&2; exit 2 ;;
esac
