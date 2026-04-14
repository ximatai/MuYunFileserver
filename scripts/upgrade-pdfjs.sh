#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VIEWER_DIR="$ROOT_DIR/frontend/viewer"
VERSION_FILE="$VIEWER_DIR/pdfjs-version.json"

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <pdfjs-version>" >&2
  echo "Example: $0 5.6.205" >&2
  exit 1
fi

VERSION="$1"
ZIP_NAME="pdfjs-${VERSION}-dist.zip"
DOWNLOAD_URL="https://github.com/mozilla/pdf.js/releases/download/v${VERSION}/${ZIP_NAME}"
TARGET_DIR="$VIEWER_DIR/vendor/pdfjs/$VERSION"

for command in curl unzip rsync node; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Missing required command: $command" >&2
    exit 1
  fi
done

TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

echo "Downloading PDF.js ${VERSION} from ${DOWNLOAD_URL}"
curl -fL "$DOWNLOAD_URL" -o "$TMP_DIR/$ZIP_NAME"

echo "Extracting release archive"
unzip -q "$TMP_DIR/$ZIP_NAME" -d "$TMP_DIR/release"

mkdir -p "$TARGET_DIR"
rsync -a --delete "$TMP_DIR/release/build/" "$TARGET_DIR/build/"
rsync -a --delete "$TMP_DIR/release/web/" "$TARGET_DIR/web/"
cp "$TMP_DIR/release/LICENSE" "$TARGET_DIR/LICENSE"

node <<'EOF' "$VERSION_FILE" "$VERSION"
const fs = require('node:fs');
const filePath = process.argv[1];
const version = process.argv[2];
const content = JSON.stringify({ version }, null, 2) + '\n';
fs.writeFileSync(filePath, content, 'utf8');
EOF

echo "Updated current PDF.js version to ${VERSION}"
echo "Vendor assets stored at ${TARGET_DIR}"
echo "Next step: ./scripts/verify-pdfjs.sh"
