#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/common.sh"

ensure_prerequisites

TOKEN_SECRET="$(fetch_remote_secret token)"

log "running health checks"
remote "curl -fsS http://127.0.0.1:9082/q/health/ready >/dev/null"
curl -fsS "$PUBLIC_BASE_URL/q/health/ready" >/dev/null

log "running end-to-end upload/read/delete check via nginx"
TOKEN_SECRET="$TOKEN_SECRET" PUBLIC_BASE_URL="$PUBLIC_BASE_URL" REMOTE_BASE_DIR="$REMOTE_BASE_DIR" ROCKY_HOST="$ROCKY_HOST" python3 - <<'PY'
import base64
import hashlib
import hmac
import json
import os
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
from urllib.error import HTTPError

base = os.environ["PUBLIC_BASE_URL"]
tenant = "tenant-a"
user = "u123"
token_secret = os.environ["TOKEN_SECRET"]
db_path = os.path.join(os.environ["REMOTE_BASE_DIR"], "var/data/muyun-fileserver.db")
rocky_host = os.environ["ROCKY_HOST"]

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")

def sign(payload: dict, secret: str) -> str:
    payload_bytes = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    sig = hmac.new(secret.encode("utf-8"), payload_bytes, hashlib.sha256).digest()
    return f"{b64url(payload_bytes)}.{b64url(sig)}"

now = int(time.time())
upload_token = sign({
    "iss": "rocky-verify",
    "sub": user,
    "tenant_id": tenant,
    "purpose": "upload",
    "exp": now + 300,
}, token_secret)

with tempfile.NamedTemporaryFile("wb", suffix=".txt", delete=False) as fh:
    fh.write(b"rocky verify upload\n")
    path = fh.name

boundary = f"----MuYunVerify{now:08x}"
with open(path, "rb") as fh:
    file_bytes = fh.read()
body = (
    f"--{boundary}\r\n"
    f"Content-Disposition: form-data; name=\"files\"; filename=\"verify.txt\"\r\n"
    f"Content-Type: text/plain\r\n\r\n"
).encode() + file_bytes + (
    f"\r\n--{boundary}\r\n"
    f"Content-Disposition: form-data; name=\"remark\"\r\n\r\n"
    f"rocky verify\r\n"
    f"--{boundary}--\r\n"
).encode()

req = urllib.request.Request(
    f"{base}/api/v1/public/files?access_token={urllib.parse.quote(upload_token)}",
    data=body,
    method="POST",
    headers={"Content-Type": f"multipart/form-data; boundary={boundary}"}
)
with urllib.request.urlopen(req, timeout=30) as resp:
    upload_resp = json.loads(resp.read().decode("utf-8"))
    file_id = upload_resp["data"]["items"][0]["id"]

meta_req = urllib.request.Request(
    f"{base}/api/v1/files/{file_id}",
    headers={"X-Tenant-Id": tenant, "X-User-Id": user}
)
with urllib.request.urlopen(meta_req, timeout=30) as resp:
    meta_resp = json.loads(resp.read().decode("utf-8"))

read_token = sign({
    "iss": "rocky-verify",
    "sub": user,
    "tenant_id": tenant,
    "file_id": file_id,
    "exp": now + 300,
}, token_secret)
with urllib.request.urlopen(
    f"{base}/api/v1/public/files/{file_id}/download?access_token={urllib.parse.quote(read_token)}",
    timeout=30
) as resp:
    downloaded = resp.read().decode("utf-8").strip()

with urllib.request.urlopen(
    f"{base}/api/v1/public/files/{file_id}/view?access_token={urllib.parse.quote(read_token)}",
    timeout=30
) as resp:
    view_resp = json.loads(resp.read().decode("utf-8"))

with urllib.request.urlopen(
    f"{base}/view/public/files/{file_id}?access_token={urllib.parse.quote(read_token)}",
    timeout=30
) as resp:
    viewer_html = resp.read().decode("utf-8")

delete_token = sign({
    "iss": "rocky-verify",
    "sub": user,
    "tenant_id": tenant,
    "file_id": file_id,
    "purpose": "delete",
    "exp": now + 300,
}, token_secret)
delete_req = urllib.request.Request(
    f"{base}/api/v1/public/files/{file_id}?access_token={urllib.parse.quote(delete_token)}",
    method="DELETE",
)
with urllib.request.urlopen(delete_req, timeout=30) as resp:
    delete_resp = json.loads(resp.read().decode("utf-8"))

query_after_delete_status = None
try:
    urllib.request.urlopen(
        f"{base}/api/v1/public/files/{file_id}?access_token={urllib.parse.quote(read_token)}",
        timeout=30
    )
except HTTPError as exc:
    query_after_delete_status = exc.code

query = f"""python3 - <<'PY'
import sqlite3
conn = sqlite3.connect('{db_path}')
cur = conn.cursor()
row = cur.execute("select storage_key from file_metadata where id = ?", ('{file_id}',)).fetchone()
print(row[0] if row else '')
PY"""
storage_key = subprocess.check_output(["ssh", rocky_host, query], text=True).strip()

result = {
    "file_id": file_id,
    "remark": meta_resp["data"]["remark"],
    "uploaded_by": meta_resp["data"]["uploadedBy"],
    "download_body": downloaded,
    "viewer_type": view_resp["data"]["viewerType"],
    "viewer_title_found": "MuYun File Viewer" in viewer_html,
    "delete_status": delete_resp["data"]["status"],
    "query_after_delete_status": query_after_delete_status,
    "storage_key": storage_key,
}
print(json.dumps(result, ensure_ascii=False, indent=2))

assert result["remark"] == "rocky verify"
assert result["uploaded_by"] == user
assert result["download_body"] == "rocky verify upload"
assert result["viewer_type"] == "text"
assert result["viewer_title_found"] is True
assert result["delete_status"] == "DELETED"
assert result["query_after_delete_status"] == 404
assert storage_key is not None and storage_key.count("/") == 3, storage_key
PY

log "verify finished"
