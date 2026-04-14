#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VIEWER_DIR="$ROOT_DIR/frontend/viewer"

echo "Building viewer frontend"
(
  cd "$VIEWER_DIR"
  npm run build
)

echo "Running backend verification build"
(
  cd "$ROOT_DIR"
  ./gradlew clean test quarkusBuild --no-daemon
)

echo "PDF.js verification completed"
