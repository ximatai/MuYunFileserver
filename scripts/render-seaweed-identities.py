#!/usr/bin/env python3
"""Render local S3 identities from environment; never print or replace secret material."""
import json
import os
from pathlib import Path
import re
import sys

formal = os.environ.get("MFS_S3_BUCKET", "muyun-files")
receiving = os.environ.get("MFS_RECEPTION_BUCKET", "muyun-recording")
if formal == receiving or any(not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket) for bucket in (formal, receiving)):
    raise ValueError("distinct valid formal and receiving buckets are required")

def identity(name, access_name, secret_name, actions):
    access, secret = os.environ[access_name], os.environ[secret_name]
    if len(access) < 12 or len(secret) < 24:
        raise ValueError("storage credentials are too short")
    return {"name": name, "credentials": [{"accessKey": access, "secretKey": secret}], "actions": actions}

identities = [identity("fileserver", "MFS_S3_ACCESS_KEY", "MFS_S3_SECRET_KEY", ["Admin", "Read", "Write", "List"]),
              identity("recorder", "AGORA_STORAGE_ACCESS_KEY", "AGORA_STORAGE_SECRET_KEY",
                       [action + ":" + receiving for action in ("Read", "Write", "List")])]
if identities[0]["credentials"][0]["accessKey"] == identities[1]["credentials"][0]["accessKey"]:
    raise ValueError("FileServer and recorder must use different access keys")
target = Path(sys.argv[1] if len(sys.argv) > 1 else "var/seaweedfs/s3.json")
target.parent.mkdir(parents=True, exist_ok=True)
with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as output:
    json.dump({"identities": identities}, output)
print("S3 identity configuration created; credentials were not printed.")
