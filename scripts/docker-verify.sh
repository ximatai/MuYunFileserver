#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_TAG="${1:-muyun-fileserver:latest}"
CONTAINER_NAME="${CONTAINER_NAME:-muyun-fileserver-verify}"
PORT="${PORT:-18080}"
RUNTIME_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/muyun-docker-verify.XXXXXX")"
CONFIG_FILE="$RUNTIME_ROOT/application.yml"

cleanup() {
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
  rm -rf "$RUNTIME_ROOT"
}
trap cleanup EXIT

mkdir -p "$RUNTIME_ROOT/data" "$RUNTIME_ROOT/storage" "$RUNTIME_ROOT/tmp"
cat > "$CONFIG_FILE" <<'EOF'
mfs:
  storage:
    type: local
    root-dir: /app/var/storage
    temp-dir: /app/var/tmp
  database:
    path: /app/var/data/muyun-fileserver.db
EOF

docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true

echo "[docker-fileserver] starting verification container"
docker run -d \
  --name "$CONTAINER_NAME" \
  -p "$PORT:8080" \
  -e QUARKUS_CONFIG_LOCATIONS=/app/application.yml \
  -v "$CONFIG_FILE:/app/application.yml:ro" \
  -v "$RUNTIME_ROOT/data:/app/var/data" \
  -v "$RUNTIME_ROOT/storage:/app/var/storage" \
  -v "$RUNTIME_ROOT/tmp:/app/var/tmp" \
  "$IMAGE_TAG" >/dev/null

echo "[docker-fileserver] waiting for readiness"
for _ in $(seq 1 60); do
  code="$(curl -s -o "$RUNTIME_ROOT/health.json" -w '%{http_code}' "http://127.0.0.1:$PORT/q/health/ready" || true)"
  if [[ "$code" == "200" ]]; then
    break
  fi
  sleep 1
done

if [[ ! -f "$RUNTIME_ROOT/health.json" ]] || ! grep -q '"status": "UP"' "$RUNTIME_ROOT/health.json"; then
  echo "[docker-fileserver] readiness failed"
  docker logs "$CONTAINER_NAME" || true
  exit 1
fi

echo "[docker-fileserver] checking soffice"
docker exec "$CONTAINER_NAME" sh -lc 'command -v soffice >/dev/null && soffice --headless --version | head -n 1'

TEXT_FILE="$RUNTIME_ROOT/smoke.txt"
printf 'docker viewer smoke\n' > "$TEXT_FILE"

echo "[docker-fileserver] uploading text file"
UPLOAD_JSON="$(curl -sS -X POST "http://127.0.0.1:$PORT/api/v1/files" \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123' \
  -F "files=@$TEXT_FILE;type=text/plain")"

FILE_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["items"][0]["id"])' <<<"$UPLOAD_JSON")"

VIEW_JSON="$(curl -sS "http://127.0.0.1:$PORT/api/v1/files/$FILE_ID/view" \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123')"

VIEW_CONTENT="$(curl -sS "http://127.0.0.1:$PORT/api/v1/files/$FILE_ID/view/content" \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123')"

DOCX_FILE="$RUNTIME_ROOT/smoke.docx"
python3 - "$DOCX_FILE" <<'PY'
import sys, zipfile
target = sys.argv[1]
content_types = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>"""
rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>"""
document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:wpc="http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas"
 xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
 xmlns:o="urn:schemas-microsoft-com:office:office"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
 xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"
 xmlns:v="urn:schemas-microsoft-com:vml"
 xmlns:wp14="http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing"
 xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
 xmlns:w10="urn:schemas-microsoft-com:office:word"
 xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
 xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml"
 xmlns:wpg="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup"
 xmlns:wpi="http://schemas.microsoft.com/office/word/2010/wordprocessingInk"
 xmlns:wne="http://schemas.microsoft.com/office/word/2006/wordml"
 xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
 mc:Ignorable="w14 wp14">
  <w:body>
    <w:p><w:r><w:t>docker office preview smoke</w:t></w:r></w:p>
    <w:sectPr>
      <w:pgSz w:w="11906" w:h="16838"/>
      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/>
    </w:sectPr>
  </w:body>
</w:document>"""
core = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
 xmlns:dc="http://purl.org/dc/elements/1.1/"
 xmlns:dcterms="http://purl.org/dc/terms/"
 xmlns:dcmitype="http://purl.org/dc/dcmitype/"
 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <dc:title>MuYun Docker Smoke</dc:title>
</cp:coreProperties>"""
app = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
 xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
  <Application>MuYunFileServer</Application>
</Properties>"""
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as zf:
    zf.writestr("[Content_Types].xml", content_types)
    zf.writestr("_rels/.rels", rels)
    zf.writestr("word/document.xml", document)
    zf.writestr("docProps/core.xml", core)
    zf.writestr("docProps/app.xml", app)
PY

echo "[docker-fileserver] uploading docx"
DOCX_UPLOAD_JSON="$(curl -sS -X POST "http://127.0.0.1:$PORT/api/v1/files" \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123' \
  -F "files=@$DOCX_FILE;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document")"

DOCX_FILE_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["items"][0]["id"])' <<<"$DOCX_UPLOAD_JSON")"
PREVIEW_CONTENT_TYPE="$(curl -sSI "http://127.0.0.1:$PORT/api/v1/files/$DOCX_FILE_ID/preview/content" \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123' | tr -d '\r' | awk -F': ' 'tolower($1)=="content-type" {print $2; exit}')"

python3 - <<'PY' "$UPLOAD_JSON" "$VIEW_JSON" "$VIEW_CONTENT" "$DOCX_FILE_ID" "$PREVIEW_CONTENT_TYPE"
import json, sys
upload = json.loads(sys.argv[1])
view = json.loads(sys.argv[2])
content = sys.argv[3].strip()
docx_file_id = sys.argv[4]
preview_content_type = sys.argv[5]
assert view["data"]["viewerType"] == "text", view
assert content == "docker viewer smoke", content
assert preview_content_type == "application/pdf", preview_content_type
print(json.dumps({
    "text_file_id": upload["data"]["items"][0]["id"],
    "text_viewer_type": view["data"]["viewerType"],
    "text_content": content,
    "docx_file_id": docx_file_id,
    "docx_preview_content_type": preview_content_type
}, ensure_ascii=False, indent=2))
PY

echo "[docker-fileserver] verify finished"
