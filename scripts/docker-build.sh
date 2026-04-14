#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_TAG="${1:-muyun-fileserver:latest}"

cd "$ROOT_DIR"

echo "[docker-fileserver] building quarkus-app"
./gradlew quarkusBuild -x test --no-daemon

echo "[docker-fileserver] building image $IMAGE_TAG"
docker build -f src/main/docker/Dockerfile.jvm -t "$IMAGE_TAG" .

echo "[docker-fileserver] image ready: $IMAGE_TAG"
