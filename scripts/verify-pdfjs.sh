#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VIEWER_DIR="$ROOT_DIR/frontend/viewer"

echo "Building viewer frontend"
(
  cd "$VIEWER_DIR"
  npm run build
)

VENDOR_VIEWER_HTML="$VIEWER_DIR/vendor/pdfjs/$(node -p "require('./frontend/viewer/pdfjs-version.json').version")/web/viewer.html"
if grep -q "MuYun PDF Viewer" "$VENDOR_VIEWER_HTML"; then
  echo "Vendor PDF.js viewer should stay official and unpatched: $VENDOR_VIEWER_HTML" >&2
  exit 1
fi
if grep -q "muyun-overrides.css" "$VENDOR_VIEWER_HTML"; then
  echo "Vendor PDF.js viewer should not reference MuYun overrides: $VENDOR_VIEWER_HTML" >&2
  exit 1
fi

PUBLIC_VIEWER_HTML="$VIEWER_DIR/public/pdfjs/web/viewer.html"
if ! grep -q "MuYun PDF Viewer" "$PUBLIC_VIEWER_HTML"; then
  echo "Expected MuYun PDF.js title patch in $PUBLIC_VIEWER_HTML" >&2
  exit 1
fi
if ! grep -q "muyun-overrides.css" "$PUBLIC_VIEWER_HTML"; then
  echo "Expected MuYun PDF.js override stylesheet in $PUBLIC_VIEWER_HTML" >&2
  exit 1
fi

echo "Running backend verification build"
(
  cd "$ROOT_DIR"
  ./gradlew clean test quarkusBuild --no-daemon
)

BUILT_VIEWER_HTML="$ROOT_DIR/build/generated-resources/viewer/META-INF/resources/viewer/pdfjs/web/viewer.html"
if ! grep -q "MuYun PDF Viewer" "$BUILT_VIEWER_HTML"; then
  echo "Expected MuYun PDF.js title patch in $BUILT_VIEWER_HTML" >&2
  exit 1
fi
if ! grep -q "muyun-overrides.css" "$BUILT_VIEWER_HTML"; then
  echo "Expected MuYun PDF.js override stylesheet in $BUILT_VIEWER_HTML" >&2
  exit 1
fi

echo "PDF.js verification completed"
