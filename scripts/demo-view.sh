#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEMO_DIR="${1:-$ROOT_DIR/demo-files}"
RUNTIME_ROOT="${RUNTIME_ROOT:-$ROOT_DIR/.local/demo-view}"
APP_PORT="${APP_PORT:-8080}"
BASE_URL="${BASE_URL:-http://127.0.0.1:$APP_PORT}"
TENANT_ID="${TENANT_ID:-tenant-a}"
USER_ID="${USER_ID:-u123}"
TOKEN_SECRET="${TOKEN_SECRET:-local-demo-secret}"
TOKEN_ISSUER="${TOKEN_ISSUER:-local-demo}"
TOKEN_TTL_DAYS="${TOKEN_TTL_DAYS:-14}"
SOFFICE_COMMAND="${SOFFICE_COMMAND:-$(command -v soffice || true)}"
PID_FILE="$RUNTIME_ROOT/quarkus-dev.pid"
LOG_FILE="$RUNTIME_ROOT/quarkus-dev.log"

if [[ ! -d "$DEMO_DIR" ]]; then
  echo "demo directory not found: $DEMO_DIR" >&2
  exit 1
fi

if [[ -z "$SOFFICE_COMMAND" ]]; then
  echo "soffice not found; set SOFFICE_COMMAND explicitly" >&2
  exit 1
fi

mkdir -p \
  "$RUNTIME_ROOT/data" \
  "$RUNTIME_ROOT/storage" \
  "$RUNTIME_ROOT/tmp" \
  "$RUNTIME_ROOT/profile"

is_ready() {
  curl -fsS "$BASE_URL/q/health/ready" >/dev/null 2>&1
}

start_app() {
  if is_ready; then
    echo "[demo-view] app already ready at $BASE_URL"
    return
  fi

  if [[ -f "$PID_FILE" ]]; then
    local existing_pid
    existing_pid="$(cat "$PID_FILE")"
    if kill -0 "$existing_pid" >/dev/null 2>&1; then
      echo "[demo-view] existing quarkusDev process found ($existing_pid), waiting for readiness"
    else
      rm -f "$PID_FILE"
    fi
  fi

  if [[ ! -f "$PID_FILE" ]]; then
    echo "[demo-view] starting quarkusDev on $BASE_URL"
    (
      cd "$ROOT_DIR"
      env \
        MFS_DATABASE_PATH="$RUNTIME_ROOT/data/muyun-fileserver-demo.db" \
        MFS_STORAGE_ROOT_DIR="$RUNTIME_ROOT/storage" \
        MFS_STORAGE_TEMP_DIR="$RUNTIME_ROOT/tmp" \
        MFS_TOKEN_ENABLED=true \
        MFS_TOKEN_SECRET="$TOKEN_SECRET" \
        MFS_TOKEN_ISSUER="$TOKEN_ISSUER" \
        MFS_PREVIEW_LIBREOFFICE_COMMAND="$SOFFICE_COMMAND" \
        MFS_PREVIEW_LIBREOFFICE_PROFILE_ROOT="$RUNTIME_ROOT/profile" \
        ./gradlew quarkusDev --no-daemon >"$LOG_FILE" 2>&1
    ) &
    echo $! >"$PID_FILE"
  fi

  for _ in $(seq 1 90); do
    if is_ready; then
      echo "[demo-view] app is ready"
      return
    fi
    sleep 1
  done

  echo "[demo-view] app did not become ready; tail of log:" >&2
  tail -n 120 "$LOG_FILE" >&2 || true
  exit 1
}

upload_and_render() {
  python3 - "$DEMO_DIR" "$BASE_URL" "$TENANT_ID" "$USER_ID" "$TOKEN_SECRET" "$TOKEN_ISSUER" "$TOKEN_TTL_DAYS" <<'PY'
import base64
import hashlib
import hmac
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.parse
import urllib.request

demo_dir = pathlib.Path(sys.argv[1])
base_url = sys.argv[2]
tenant_id = sys.argv[3]
user_id = sys.argv[4]
token_secret = sys.argv[5].encode()
token_issuer = sys.argv[6]
token_ttl_days = int(sys.argv[7])

def encode_urlsafe(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")

def make_token(file_id: str) -> str:
    payload = {
        "iss": token_issuer,
        "sub": user_id,
        "tenant_id": tenant_id,
        "file_id": file_id,
        "exp": int(time.time()) + token_ttl_days * 24 * 3600,
    }
    payload_bytes = json.dumps(payload, separators=(",", ":")).encode()
    sig = hmac.new(token_secret, payload_bytes, hashlib.sha256).digest()
    return f"{encode_urlsafe(payload_bytes)}.{encode_urlsafe(sig)}"

for path in sorted(p for p in demo_dir.iterdir() if p.is_file() and not p.name.startswith(".")):
    upload_raw = subprocess.check_output([
        "curl", "-fsS",
        "-X", "POST", f"{base_url}/api/v1/files",
        "-H", f"X-Tenant-Id: {tenant_id}",
        "-H", f"X-User-Id: {user_id}",
        "-F", f"files=@{path}",
    ], text=True)
    upload_data = json.loads(upload_raw)
    item = upload_data["data"]["items"][0]
    file_id = item["id"]
    token = make_token(file_id)
    query_token = urllib.parse.quote(token, safe="")
    with urllib.request.urlopen(f"{base_url}/api/v1/public/files/{file_id}/view?access_token={query_token}") as resp:
        view_data = json.load(resp)["data"]
    view_url = f"{base_url}/view/public/files/{file_id}?access_token={token}"
    print("\t".join([
        path.name,
        file_id,
        item["mimeType"],
        view_data["viewerType"],
        view_url,
    ]))
PY
}

start_app
echo "[demo-view] uploaded file links:"
printf 'filename\tfileId\tmimeType\tviewerType\tviewUrl\n'
upload_and_render
echo "[demo-view] pid file: $PID_FILE"
echo "[demo-view] log file: $LOG_FILE"
