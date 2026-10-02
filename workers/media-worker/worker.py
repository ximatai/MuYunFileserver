#!/usr/bin/env python3
"""Standalone, default-local recording processor. FileServer owns the assets, not this queue."""
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib import request, error, parse


class Files:
    def __init__(self, base, tenant, token):
        endpoint = parse.urlsplit(base)
        if endpoint.scheme not in ("http", "https") or endpoint.username or endpoint.query or endpoint.fragment:
            raise ValueError("invalid fixed FileServer endpoint")
        if endpoint.scheme == "http" and endpoint.hostname not in ("localhost", "127.0.0.1"):
            raise ValueError("FileServer HTTP is permitted only on loopback")
        self.base, self.tenant, self.token = base.rstrip("/"), tenant, token
        self.opener = request.build_opener(NoRedirect())

    def call(self, method, path, body=None, binary=False):
        headers = {"X-Tenant-Id": self.tenant, "X-User-Id": "media-worker", "Authorization": "Bearer " + self.token}
        if body is not None:
            headers["Content-Type"] = "application/octet-stream" if binary else "application/json"
            if not binary:
                body = json.dumps(body).encode()
        req = request.Request(self.base + path, data=body, headers=headers, method=method)
        with self.opener.open(req, timeout=60) as response:
            raw = response.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise ValueError("oversized FileServer JSON response")
            return json.loads(raw)["data"]

    def download(self, asset, destination, max_bytes):
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", asset):
            raise ValueError("invalid file asset")
        metadata = self.call("GET", "/api/v1/files/" + asset)
        if metadata["sizeBytes"] > max_bytes:
            raise ValueError("recording exceeds worker capacity")
        digest = hashlib.sha256()
        req = request.Request(self.base + "/api/v1/files/" + asset + "/download", headers={
            "X-Tenant-Id": self.tenant, "X-User-Id": "media-worker"})
        with self.opener.open(req, timeout=60) as response, destination.open("wb") as output:
            size = 0
            while chunk := response.read(65536):
                size += len(chunk)
                if size > max_bytes:
                    raise ValueError("recording exceeds worker capacity")
                output.write(chunk)
                digest.update(chunk)
        if size != metadata["sizeBytes"] or digest.hexdigest() != metadata["sha256"]:
            raise ValueError("recording download checksum mismatch")
        return size


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def validate_hls(path, members):
    """Reject remote references, encryption and truncated/live indexes before invoking ffmpeg."""
    raw = path.read_text(encoding="utf-8")
    if len(raw) > 1024 * 1024 or not raw.startswith("#EXTM3U") or "#EXT-X-ENDLIST" not in raw:
        raise ValueError("recording index is incomplete")
    if "#EXT-X-KEY" in raw or "#EXT-X-MAP" in raw or "#EXT-X-STREAM-INF" in raw:
        raise ValueError("unsupported recording index; preserve raw assets for later processing")
    segments = [line.strip() for line in raw.splitlines() if line.strip() and not line.startswith("#")]
    if not segments:
        raise ValueError("recording index contains no fragments")
    for segment in segments:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*", segment) or any(p in (".", "..") for p in segment.split("/")):
            raise ValueError("unsafe HLS reference")
        relative = (path.parent / segment).resolve()
        if relative not in members:
            raise ValueError("recording fragment is missing from confirmed assets")


class ReceptionBusy(Exception):
    """Another request still owns confirmation; keep the frozen manifest and retry."""


class Worker:
    def __init__(self, root, files, ffmpeg="ffmpeg", ffprobe="ffprobe", max_bytes=16 * 1024 ** 3):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.files, self.ffmpeg, self.ffprobe, self.max_bytes = files, ffmpeg, ffprobe, max_bytes
        self.lock = threading.Lock()
        with self.db() as db:
            db.execute("create table if not exists jobs(id text primary key,video integer,state text,result text,error text,updated real)")
            # This executable runs one worker thread and holds an exclusive process lock (see main).
            db.execute("update jobs set state='QUEUED' where state='PROCESSING'")

    def db(self):
        return sqlite3.connect(self.root / "jobs.db", timeout=30)

    def submit(self, reception, video):
        if not re.fullmatch(r"[0-9a-f-]{36}", reception) or not isinstance(video, bool):
            raise ValueError("invalid recording job")
        with self.db() as db:
            db.execute("insert or ignore into jobs values(?,?,'QUEUED',null,null,?)", (reception, int(video), time.time()))
            if db.execute("select video from jobs where id=?", (reception,)).fetchone()[0] != int(video):
                raise ValueError("job id reused with different profile")
        return self.get(reception)

    def get(self, reception):
        with self.db() as db:
            row = db.execute("select state,result,error from jobs where id=?", (reception,)).fetchone()
        if row is None:
            raise KeyError(reception)
        return {"id": reception, "state": row[0], **(json.loads(row[1]) if row[1] else {}), **({"error": row[2]} if row[2] else {})}

    def process_next(self):
        with self.lock:
            with self.db() as db:
                row = db.execute("select id,video from jobs where state='QUEUED' and updated<=? order by updated limit 1", (time.time(),)).fetchone()
                if row is None:
                    return False
                db.execute("update jobs set state='PROCESSING',updated=? where id=?", (time.time(), row[0]))
            try:
                result = self.process(row[0], bool(row[1]))
                with self.db() as db:
                    db.execute("update jobs set state='READY',result=?,error=null,updated=? where id=?", (json.dumps(result), time.time(), row[0]))
                shutil.rmtree(self.root / row[0], ignore_errors=True)
            except Exception as exc:
                # Never log provider responses, URLs or credentials. Explicit retry uses the same durable files/manifest.
                with self.db() as db:
                    transient = isinstance(exc, ReceptionBusy) or isinstance(exc, (error.URLError, TimeoutError)) and (not isinstance(exc, error.HTTPError) or exc.code >= 500)
                    db.execute("update jobs set state=?,error=?,updated=? where id=?",
                               ("QUEUED" if transient else "FAILED", type(exc).__name__, time.time() + (30 if transient else 0), row[0]))
            return True

    def process(self, reception, video):
        task = self.files.call("GET", "/api/v1/internal/receptions/" + reception)
        if task["state"] != "READY" or not task["assets"]:
            raise ValueError("raw recording reception is not confirmed")
        directory = self.root / reception
        directory.mkdir(exist_ok=True)
        manifest_file = directory / "derived-manifest.json"
        if not manifest_file.exists():
            remaining = self.max_bytes
            members = set()
            for key, asset in task["assets"].items():
                if not re.fullmatch(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*", key) or any(p in (".", "..") for p in key.split("/")):
                    raise ValueError("unsafe recording asset key")
                path = directory / "raw" / key
                path.parent.mkdir(parents=True, exist_ok=True)
                remaining -= self.files.download(asset, path, remaining)
                members.add(path.resolve())
            indexes = sorted(path for path in members if path.suffix == ".m3u8")
            if len(indexes) != 1:
                raise ValueError("expected one mixed recording index; keep all raw indexes")
            validate_hls(indexes[0], members)
            audio = directory / "audio.ogg"
            self.run([self.ffmpeg,"-nostdin","-y","-v","error","-xerror","-protocol_whitelist","file,crypto", "-i",str(indexes[0]),
                      "-map","0:a:0","-vn","-af","asetpts=N/SR/TB","-ac","1","-c:a","libopus","-b:a","32k",str(audio)])
            paths = [audio]
            if video:
                movie = directory / "video.mp4"
                self.run([self.ffmpeg,"-nostdin","-y","-v","error","-xerror","-protocol_whitelist","file,crypto","-i",str(indexes[0]),
                          "-map","0:v:0","-map","0:a:0?","-c","copy","-movflags","+faststart",str(movie)])
                paths.append(movie)
                self.run([self.ffmpeg,"-nostdin","-v","error","-xerror","-i",str(movie),"-f","null","-"])
            facts = self.probe(audio)
            manifest = {"objects": [self.entry(path) for path in paths], "facts": facts}
            staging = directory / "manifest.part"
            staging.write_text(json.dumps(manifest), encoding="utf-8")
            os.replace(staging, manifest_file)
        manifest = json.loads(manifest_file.read_text())
        derived = self.files.call("POST", "/api/v1/internal/receptions", {
            "idempotencyKey": "derived_" + reception, "maxObjects": 2, "maxBytes": self.max_bytes, "expiresInSeconds": 604800})
        if derived["state"] == "OPEN":
            for item in manifest["objects"]:
                path = directory / item["key"]
                # urllib streams a file object in bounded chunks; do not load recordings into RAM.
                try:
                    with path.open("rb") as source:
                        self.files.call("PUT", "/api/v1/internal/receptions/" + derived["id"] + "/objects/" + item["key"], source, binary=True)
                except error.HTTPError as exc:
                    if exc.code != 409:
                        raise
                    exc.close()
                    current = self.files.call("GET", "/api/v1/internal/receptions/" + derived["id"])
                    if current["state"] == "CONFIRMING":
                        raise ReceptionBusy() from exc
                    if current["state"] == "READY":
                        break  # The frozen manifest is checked by confirm below.
                    raise
        if derived["state"] not in ("OPEN", "CONFIRMING", "READY"):
            raise ValueError("derived reception is no longer usable")
        try:
            confirmed = self.files.call("POST", "/api/v1/internal/receptions/" + derived["id"] + "/confirm", {"objects": manifest["objects"]})
        except error.HTTPError as exc:
            if exc.code == 409:
                exc.close()
                current = self.files.call("GET", "/api/v1/internal/receptions/" + derived["id"])
                if current["state"] == "READY":
                    # Confirm again to check the frozen manifest, rather than accepting unknown assets.
                    confirmed = self.files.call("POST", "/api/v1/internal/receptions/" + derived["id"] + "/confirm", {"objects": manifest["objects"]})
                elif current["state"] == "CONFIRMING":
                    raise ReceptionBusy() from exc
                else:
                    raise
            else:
                raise
        return {"assets": confirmed["assets"], "derivedReceptionId": derived["id"], **manifest["facts"]}

    def run(self, args):
        with (self.root / "process.log").open("wb") as log:
            subprocess.run(args, stdin=subprocess.DEVNULL, stdout=log, stderr=log, check=True, timeout=600)

    def probe(self, path):
        # Decode the entire output, then inspect codec and duration. Metadata alone is insufficient.
        self.run([self.ffmpeg,"-nostdin","-v","error","-xerror","-i",str(path),"-f","null","-"])
        output = subprocess.check_output([self.ffprobe,"-v","error","-show_entries","format=duration:stream=codec_name,sample_rate,channels",
                                         "-select_streams","a:0","-of","json",str(path)], timeout=30)
        facts = json.loads(output); stream = facts["streams"][0]
        duration = round(float(facts["format"]["duration"]) * 1000)
        if duration <= 0 or int(stream["sample_rate"]) <= 0 or stream["channels"] <= 0:
            raise ValueError("invalid processed audio")
        return {"durationMillis": duration, "codec": stream["codec_name"], "sampleRate": int(stream["sample_rate"]), "channels": stream["channels"]}

    @staticmethod
    def entry(path):
        digest = hashlib.sha256()
        with path.open("rb") as source:
            while chunk := source.read(65536):
                digest.update(chunk)
        return {"key": path.name, "filename": path.name, "sizeBytes": path.stat().st_size, "sha256": digest.hexdigest()}


def main():
    import fcntl
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8096)
    parser.add_argument("--root", default="var/media-worker")
    args = parser.parse_args()
    token = os.environ["MEDIA_WORKER_TOKEN"]
    if len(token) < 24:
        raise ValueError("worker service token must have at least 24 characters")
    root = Path(args.root).resolve(); root.mkdir(parents=True, exist_ok=True)
    lock = (root / "worker.lock").open("w")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    worker = Worker(root, Files(os.environ["FILESERVER_URL"], os.environ["FILESERVER_TENANT"], os.environ["FILESERVER_RECEPTION_TOKEN"]))

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def respond(self, status, value):
            raw = json.dumps(value).encode(); self.send_response(status)
            self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store"); self.end_headers(); self.wfile.write(raw)

        def authenticated(self):
            return hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + token)

        def do_GET(self):
            if not self.authenticated():
                return self.respond(401, {})
            match = re.fullmatch(r"/jobs/([0-9a-f-]{36})", self.path)
            try:
                if not match:
                    return self.respond(404, {})
                self.respond(200, worker.get(match[1]))
            except KeyError:
                self.respond(404, {})

        def do_POST(self):
            if not self.authenticated():
                return self.respond(401, {})
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if self.path != "/jobs" or not 0 < size <= 2048:
                    return self.respond(400, {})
                body = json.loads(self.rfile.read(size))
                self.respond(200, worker.submit(body["receptionId"], body["video"]))
            except (ValueError, KeyError, TypeError):
                self.respond(400, {})

    def consume():
        while True:
            if not worker.process_next():
                time.sleep(1)
    threading.Thread(target=consume, daemon=True).start()
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
