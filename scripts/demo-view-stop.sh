#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME_ROOT="${RUNTIME_ROOT:-$ROOT_DIR/.local/demo-view}"
PID_FILE="$RUNTIME_ROOT/quarkus-dev.pid"
APP_PORT="${APP_PORT:-8080}"

resolve_pid() {
  if [[ -f "$PID_FILE" ]]; then
    cat "$PID_FILE"
    return 0
  fi

  lsof -tiTCP:"$APP_PORT" -sTCP:LISTEN 2>/dev/null | head -n 1 || true
}

PID="$(resolve_pid)"
if [[ -z "$PID" ]]; then
  echo "[demo-view-stop] no demo-view process found"
  rm -f "$PID_FILE"
  exit 0
fi

if [[ -f "$PID_FILE" && ! -s "$PID_FILE" ]]; then
  echo "[demo-view-stop] pid file is empty; removing $PID_FILE"
  rm -f "$PID_FILE"
  exit 0
fi

if ! kill -0 "$PID" >/dev/null 2>&1; then
  echo "[demo-view-stop] process $PID is not running; removing stale pid file"
  rm -f "$PID_FILE"
  exit 0
fi

echo "[demo-view-stop] stopping quarkusDev process $PID"
kill "$PID"

for _ in $(seq 1 30); do
  if ! kill -0 "$PID" >/dev/null 2>&1; then
    rm -f "$PID_FILE"
    echo "[demo-view-stop] stopped"
    exit 0
  fi
  sleep 1
done

echo "[demo-view-stop] process $PID did not exit in time; sending SIGKILL"
kill -9 "$PID" >/dev/null 2>&1 || true
rm -f "$PID_FILE"
echo "[demo-view-stop] stopped"
