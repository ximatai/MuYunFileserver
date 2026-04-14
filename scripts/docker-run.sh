#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_TAG="${1:-muyun-fileserver:latest}"
CONTAINER_NAME="${CONTAINER_NAME:-muyun-fileserver}"
PORT="${PORT:-8080}"
RUNTIME_ROOT="${RUNTIME_ROOT:-$ROOT_DIR/.local/docker-fileserver/runtime}"
CONFIG_FILE="${CONFIG_FILE:-$ROOT_DIR/distribution/release/application.local.example.yml}"

mkdir -p "$RUNTIME_ROOT/data" "$RUNTIME_ROOT/storage" "$RUNTIME_ROOT/tmp"

docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true

echo "[docker-fileserver] starting container $CONTAINER_NAME from $IMAGE_TAG"
docker run -d \
  --name "$CONTAINER_NAME" \
  -p "$PORT:8080" \
  -e QUARKUS_CONFIG_LOCATIONS=/app/application.yml \
  -v "$CONFIG_FILE:/app/application.yml:ro" \
  -v "$RUNTIME_ROOT/data:/app/var/data" \
  -v "$RUNTIME_ROOT/storage:/app/var/storage" \
  -v "$RUNTIME_ROOT/tmp:/app/var/tmp" \
  "$IMAGE_TAG"
