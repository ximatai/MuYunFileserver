#!/usr/bin/env python3
"""Standalone, default-local recording processor. FileServer owns the assets, not this queue."""
import argparse
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import sqlite3
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib import request, error, parse


JOB_ID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"


class Files:
    def __init__(self, base, tenant, token, space_check=None):
        endpoint = parse.urlsplit(base)
        if endpoint.scheme not in ("http", "https") or endpoint.username or endpoint.query or endpoint.fragment:
            raise ValueError("invalid fixed FileServer endpoint")
        if endpoint.scheme == "http" and endpoint.hostname not in ("localhost", "127.0.0.1"):
            raise ValueError("FileServer HTTP is permitted only on loopback")
        if not isinstance(tenant, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", tenant):
            raise ValueError("invalid FileServer tenant")
        self.base, self.tenant, self.token = base.rstrip("/"), tenant, token
        self.space_check = space_check or (lambda: None)
        self.opener = request.build_opener(request.ProxyHandler({}), NoRedirect())

    def for_tenant(self, tenant):
        return Files(self.base, tenant, self.token, self.space_check)

    def call(self, method, path, body=None, binary=False):
        self.space_check()
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
            self.space_check()
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
                self.space_check()
                size += len(chunk)
                if size > max_bytes:
                    raise ValueError("recording exceeds worker capacity")
                output.write(chunk)
                digest.update(chunk)
        if size != metadata["sizeBytes"] or digest.hexdigest() != metadata["sha256"]:
            raise ValueError("recording download checksum mismatch")
        self.space_check()
        return size


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def validate_hls(path, members):
    """Reject remote references, encryption and truncated/live indexes before invoking ffmpeg."""
    raw = path.read_text(encoding="utf-8")
    if len(raw) > 1024 * 1024 or not raw.startswith("#EXTM3U") or "#EXT-X-ENDLIST" not in raw:
        raise ValueError("recording index is incomplete")
    if any(tag in raw for tag in ("#EXT-X-KEY", "#EXT-X-MAP", "#EXT-X-STREAM-INF", "#EXT-X-BYTERANGE")):
        raise ValueError("unsupported recording index; preserve raw assets for later processing")
    segments = []
    duration = None
    for line in raw.splitlines():
        line = line.strip()
        if line.startswith("#EXTINF:"):
            value = line[8:].split(",", 1)[0]
            if duration is not None or not re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", value) or not math.isfinite(float(value)) or float(value) <= 0:
                raise ValueError("invalid HLS fragment duration")
            duration = value
        elif line and not line.startswith("#"):
            if duration is None:
                raise ValueError("HLS fragment has no duration")
            segments.append((line, duration))
            duration = None
    if duration is not None:
        raise ValueError("HLS duration has no fragment")
    if not segments:
        raise ValueError("recording index contains no fragments")
    for segment, duration in segments:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*", segment) or any(p in (".", "..") for p in segment.split("/")):
            raise ValueError("unsafe HLS reference")
        relative = (path.parent / segment).resolve()
        if relative not in members:
            raise ValueError("recording fragment is missing from confirmed assets")
    return [((path.parent / segment).resolve(), duration) for segment, duration in segments]


class ReceptionBusy(Exception):
    """Another request still owns confirmation; keep the frozen manifest and retry."""


class WorkerStopping(ReceptionBusy):
    """A graceful shutdown preserves the task for the next process."""


class JobTimeout(Exception):
    """The total processing deadline has expired."""


class CapacityExceeded(Exception):
    """Configured queue or local storage capacity has been reached."""


class Worker:
    def __init__(self, root, files, ffmpeg="ffmpeg", ffprobe="ffprobe", max_bytes=16 * 1024 ** 3,
                 allowed_tenants=None, max_pending=128, max_attempts=5, retry_seconds=30,
                 max_work_bytes=32 * 1024 ** 3, min_free_bytes=256 * 1024 ** 2, command_timeout=600, job_timeout=3600):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.files, self.ffmpeg, self.ffprobe, self.max_bytes = files, ffmpeg, ffprobe, max_bytes
        self.max_pending, self.max_attempts, self.retry_seconds = max_pending, max_attempts, retry_seconds
        self.max_work_bytes, self.min_free_bytes, self.command_timeout = max_work_bytes, min_free_bytes, command_timeout
        self.job_timeout = job_timeout
        self.execution = threading.local()
        self.stop_event = threading.Event()
        self.consumer_thread = None
        self.consumer_error = None
        if any(not isinstance(v, int) or isinstance(v, bool) or v < 1 for v in
               (max_bytes, max_pending, max_attempts, retry_seconds, max_work_bytes, command_timeout, job_timeout)) or min_free_bytes < 0:
            raise ValueError("invalid worker capacity or retry limits")
        if isinstance(files, Files):
            self.files = Files(files.base, files.tenant, files.token, self.check_space)
        self.default_tenant = getattr(files, "tenant", "default")
        self.allowed_tenants = frozenset(allowed_tenants if allowed_tenants is not None else [self.default_tenant])
        if (not self.allowed_tenants or len(self.allowed_tenants) > 64 or self.default_tenant not in self.allowed_tenants
                or any(not isinstance(t, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", t)
                       for t in self.allowed_tenants)):
            raise ValueError("invalid explicit worker tenant scopes")
        self.lock = threading.Lock()
        with self.db() as db:
            db.execute("begin immediate")
            db.execute("create table if not exists worker_metadata(key text primary key,value text not null)")
            db.execute("insert or ignore into worker_metadata values('legacy_tenant',?)", (self.default_tenant,))
            legacy_tenant = db.execute("select value from worker_metadata where key='legacy_tenant'").fetchone()[0]
            db.execute("create table if not exists jobs(id text primary key,video integer,state text,result text,error text,updated real,tenant text not null)")
            if "tenant" not in {row[1] for row in db.execute("pragma table_info(jobs)")}:
                db.execute("alter table jobs add column tenant text")
            if "attempts" not in {row[1] for row in db.execute("pragma table_info(jobs)")}:
                db.execute("alter table jobs add column attempts integer not null default 0")
            # Freeze the original single-tenant scope once, not the new HTTP caller or a changed default.
            db.execute("update jobs set tenant=? where tenant is null", (legacy_tenant,))
            # This executable runs one worker thread and holds an exclusive process lock (see main).
            db.execute("update jobs set state='QUEUED' where state='PROCESSING'")
            ready = [row[0] for row in db.execute("select id from jobs where state='READY'")]
        for reception in ready:
            if re.fullmatch(JOB_ID, reception):
                shutil.rmtree(self.root / reception, ignore_errors=True)

    def db(self):
        return sqlite3.connect(self.root / "jobs.db", timeout=30)

    def check_space(self):
        if self.stop_event.is_set():
            raise WorkerStopping()
        if time.monotonic() > getattr(self.execution, 'deadline', float('inf')):
            raise JobTimeout("worker processing deadline exceeded")
        if shutil.disk_usage(self.root).free < self.min_free_bytes:
            raise CapacityExceeded("worker free space threshold reached")
        now = time.monotonic()
        if now - getattr(self.execution, 'last_space_scan', -float('inf')) < 0.2:
            return
        used = 0
        for path in self.root.rglob('*'):
            try:
                if path.is_file():
                    used += path.stat().st_size
            except FileNotFoundError:
                pass  # Concurrent cleanup of a completed job is harmless.
        if used >= self.max_work_bytes:
            raise CapacityExceeded("worker storage threshold reached")
        self.execution.last_space_scan = now

    def require_queue_capacity(self, db):
        count = db.execute("select count(*) from jobs where state in ('QUEUED','PROCESSING')").fetchone()[0]
        if count >= self.max_pending:
            raise CapacityExceeded("worker queue is full")

    def emit(self, event, **fields):
        try:
            print(json.dumps({"event": event, **fields}, separators=(',', ':')), flush=True)
        except (OSError, ValueError):
            pass  # Logging failure must not change durable task state.

    def metrics(self, tenant):
        tenant = self.tenant(tenant)
        with self.db() as db:
            states = dict(db.execute("select state,count(*) from jobs where tenant=? group by state", (tenant,)))
            oldest = db.execute("select min(updated) from jobs where tenant=? and state='QUEUED'", (tenant,)).fetchone()[0]
            pending = db.execute("select count(*) from jobs where state in ('QUEUED','PROCESSING')").fetchone()[0]
        return {"tenantId": tenant, "states": states, "pendingTotal": pending, "maxPending": self.max_pending,
                "oldestQueuedAgeSeconds": max(0, int(time.time() - oldest)) if oldest is not None else 0}

    def readiness(self, tenant):
        consumer = self.consumer_thread is not None and self.consumer_thread.is_alive() and self.consumer_error is None
        try:
            metrics = self.metrics(tenant)
            database = True
        except sqlite3.Error:
            metrics, database = {}, False
        try:
            self.check_space()
            storage = True
        except (CapacityExceeded, WorkerStopping, OSError):
            storage = False
        ready = consumer and database and storage and metrics.get('pendingTotal', self.max_pending) < self.max_pending
        return {"status": "UP" if ready else "DOWN", "consumerAlive": consumer,
                "database": database, "storage": storage, **metrics}

    def consume(self):
        while not self.stop_event.is_set():
            try:
                worked = self.process_next()
                self.consumer_error = None
                if not worked:
                    self.stop_event.wait(0.5)
            except Exception as exc:
                self.consumer_error = type(exc).__name__
                self.emit('consumer_error', error=self.consumer_error)
                self.stop_event.wait(1)

    def tenant(self, value=None):
        if value is None:
            if len(self.allowed_tenants) != 1:
                raise ValueError("an explicit tenant is required")
            return self.default_tenant
        if value not in self.allowed_tenants:
            raise PermissionError("tenant is not configured")
        return value

    def submit(self, reception, video, tenant=None):
        tenant = self.tenant(tenant)
        if not isinstance(reception, str) or not re.fullmatch(JOB_ID, reception) or not isinstance(video, bool):
            raise ValueError("invalid recording job")
        with self.db() as db:
            existing = db.execute("select video,tenant from jobs where id=?", (reception,)).fetchone()
        if existing is not None:
            if existing[1] != tenant:
                raise KeyError(reception)
            if existing[0] != int(video):
                raise ValueError("job id reused with different profile")
            return self.get(reception, tenant)
        # A tenant header selects a permitted scope, not ownership of arbitrary receipt IDs.
        # Verify the source before claiming its globally unique ID in the durable queue.
        self.check_space()
        task = self.files_for(tenant).call("GET", "/api/v1/internal/receptions/" + reception)
        if task["state"] != "READY" or not task["assets"]:
            raise ValueError("raw recording reception is not confirmed")
        with self.db() as db:
            db.execute("begin immediate")
            if db.execute("select 1 from jobs where id=?", (reception,)).fetchone() is None:
                self.require_queue_capacity(db)
            db.execute("insert or ignore into jobs(id,video,state,result,error,updated,tenant) values(?,?,'QUEUED',null,null,?,?)",
                       (reception, int(video), time.time(), tenant))
            row = db.execute("select video,tenant from jobs where id=?", (reception,)).fetchone()
            if row[1] != tenant:
                raise KeyError(reception)
            if row[0] != int(video):
                raise ValueError("job id reused with different profile")
        self.emit("job_submitted", jobId=reception, tenantId=tenant)
        return self.get(reception, tenant)

    def files_for(self, tenant):
        tenant = self.tenant(tenant)
        return self.files if tenant == self.default_tenant else self.files.for_tenant(tenant)

    def get(self, reception, tenant=None):
        tenant = self.tenant(tenant)
        with self.db() as db:
            row = db.execute("select state,result,error,attempts from jobs where id=? and tenant=?", (reception, tenant)).fetchone()
        if row is None:
            raise KeyError(reception)
        return {**(json.loads(row[1]) if row[1] else {}), "id": reception, "tenantId": tenant, "state": row[0], "attempts": row[3],
                **({"error": row[2]} if row[2] else {})}

    def retry(self, reception, tenant=None):
        tenant = self.tenant(tenant)
        with self.db() as db:
            db.execute("begin immediate")
            row = db.execute("select state from jobs where id=? and tenant=?", (reception, tenant)).fetchone()
            if row is None:
                raise KeyError(reception)
            if row[0] == 'FAILED':
                self.check_space()
                self.require_queue_capacity(db)
                db.execute("update jobs set state='QUEUED',attempts=0,error=null,updated=? where id=? and tenant=?",
                           (time.time(), reception, tenant))
            # Retrying an already queued/running/ready task is idempotent, never resets its budget.
        self.emit("job_retry_requested", jobId=reception, tenantId=tenant)
        return self.get(reception, tenant)

    def process_next(self):
        with self.lock:
            with self.db() as db:
                scopes = sorted(self.allowed_tenants)
                row = db.execute("select id,video,tenant,attempts from jobs where state='QUEUED' and updated<=? and tenant in ("
                                 + ",".join("?" for _ in scopes) + ") order by updated limit 1", (time.time(), *scopes)).fetchone()
                if row is None:
                    return False
                if row[3] >= self.max_attempts:
                    db.execute("update jobs set state='FAILED',error='RetryExhausted',updated=? where id=?", (time.time(), row[0]))
                    return True
                db.execute("update jobs set state='PROCESSING',attempts=attempts+1,updated=? where id=?", (time.time(), row[0]))
            try:
                self.execution.log_path = self.root / row[0] / "process.log"
                self.emit("job_started", jobId=row[0], tenantId=row[2], attempt=row[3] + 1)
                self.execution.deadline = time.monotonic() + self.job_timeout
                self.check_space()
                result = self.process(row[0], bool(row[1]), row[2])
                self.check_space()
                with self.db() as db:
                    db.execute("update jobs set state='READY',result=?,error=null,updated=? where id=?", (json.dumps(result), time.time(), row[0]))
                self.emit("job_ready", jobId=row[0], tenantId=row[2], attempt=row[3] + 1)
                shutil.rmtree(self.root / row[0], ignore_errors=True)
            except Exception as exc:
                # Never log provider responses, URLs or credentials. Explicit retry uses the same durable files/manifest.
                with self.db() as db:
                    transient = isinstance(exc, ReceptionBusy) or isinstance(exc, (error.URLError, TimeoutError)) and (not isinstance(exc, error.HTTPError) or exc.code >= 500)
                    stopping = isinstance(exc, WorkerStopping)
                    retry = stopping or transient and row[3] + 1 < self.max_attempts
                    reason = "RetryExhausted" if transient and not retry else type(exc).__name__
                    db.execute("update jobs set state=?,error=?,updated=?,attempts=attempts-? where id=?",
                               ("QUEUED" if retry else "FAILED", reason, time.time() + (self.retry_seconds if retry else 0), int(stopping), row[0]))
                self.emit("job_deferred" if retry else "job_failed", jobId=row[0], tenantId=row[2], error=reason)
            finally:
                self.execution.deadline = float("inf")
            return True

    def process(self, reception, video, tenant=None):
        tenant = self.tenant(tenant)
        files = self.files_for(tenant)
        task = files.call("GET", "/api/v1/internal/receptions/" + reception)
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
                remaining -= files.download(asset, path, remaining)
                members.add(path.resolve())
            indexes = sorted(path for path in members if path.suffix == ".m3u8")
            if len(indexes) != 1:
                raise ValueError("expected one mixed recording index; keep all raw indexes")
            segments = validate_hls(indexes[0], members)
            # Each TS fragment has its own transport continuity counters. Opening them
            # separately avoids treating a reset at a file boundary as packet damage.
            # EXTINF, rather than the TS audio/video envelope, defines the next boundary.
            concat = directory / "source.ffconcat"
            concat.write_text("ffconcat version 1.0\n" + "".join(
                "file '" + path.relative_to(directory.resolve()).as_posix() + "'\nduration " + duration + "\n"
                for path, duration in segments), encoding="utf-8")
            input_args = ["-protocol_whitelist", "file", "-f", "concat", "-safe", "1", "-i", str(concat)]
            audio = directory / "audio.ogg"
            self.run([self.ffmpeg,"-nostdin","-y","-v","error","-xerror", *input_args,
                      "-map","0:a:0","-vn","-af","asetpts=N/SR/TB","-ac","1","-c:a","libopus","-b:a","32k",str(audio)])
            paths = [audio]
            if video:
                movie = directory / "video.mp4"
                rate = self.video_rate(segments[0][0])
                self.run([self.ffmpeg,"-nostdin","-y","-v","error","-xerror", *input_args,
                          "-map","0:v:0","-map","0:a:0?","-vf","setpts=N/(" + rate + "*TB)",
                          "-fps_mode","passthrough","-c:v","libx264","-preset","veryfast","-crf","18","-c:a","aac",
                          "-af","asetpts=N/SR/TB","-movflags","+faststart",str(movie)])
                paths.append(movie)
                self.run([self.ffmpeg,"-nostdin","-v","error","-xerror","-i",str(movie),"-f","null","-"])
            facts = self.probe(audio)
            if video:
                output = subprocess.check_output([self.ffprobe, "-v", "error", "-show_entries",
                    "format=duration", "-of", "json", str(movie)], timeout=30)
                video_duration = float(json.loads(output)["format"]["duration"])
                expected_duration = sum(float(duration) for _, duration in segments)
                if not math.isfinite(video_duration) or max(abs(video_duration - facts["durationMillis"] / 1000),
                        abs(video_duration - expected_duration)) > 0.25:
                    raise ValueError("recording audio/video timeline mismatch")
            manifest = {"objects": [self.entry(path) for path in paths], "facts": facts}
            staging = directory / "manifest.part"
            staging.write_text(json.dumps(manifest), encoding="utf-8")
            os.replace(staging, manifest_file)
        manifest = json.loads(manifest_file.read_text())
        derived = files.call("POST", "/api/v1/internal/receptions", {
            "idempotencyKey": "derived_" + reception, "maxObjects": 2, "maxBytes": self.max_bytes, "expiresInSeconds": 604800})
        if derived["state"] == "OPEN":
            for item in manifest["objects"]:
                path = directory / item["key"]
                # urllib streams a file object in bounded chunks; do not load recordings into RAM.
                try:
                    with path.open("rb") as source:
                        files.call("PUT", "/api/v1/internal/receptions/" + derived["id"] + "/objects/" + item["key"], source, binary=True)
                except error.HTTPError as exc:
                    if exc.code != 409:
                        raise
                    exc.close()
                    current = files.call("GET", "/api/v1/internal/receptions/" + derived["id"])
                    if current["state"] == "CONFIRMING":
                        raise ReceptionBusy() from exc
                    if current["state"] == "READY":
                        break  # The frozen manifest is checked by confirm below.
                    raise
        if derived["state"] not in ("OPEN", "CONFIRMING", "READY"):
            raise ValueError("derived reception is no longer usable")
        try:
            confirmed = files.call("POST", "/api/v1/internal/receptions/" + derived["id"] + "/confirm", {"objects": manifest["objects"]})
        except error.HTTPError as exc:
            if exc.code == 409:
                exc.close()
                current = files.call("GET", "/api/v1/internal/receptions/" + derived["id"])
                if current["state"] == "READY":
                    # Confirm again to check the frozen manifest, rather than accepting unknown assets.
                    confirmed = files.call("POST", "/api/v1/internal/receptions/" + derived["id"] + "/confirm", {"objects": manifest["objects"]})
                elif current["state"] == "CONFIRMING":
                    raise ReceptionBusy() from exc
                else:
                    raise
            else:
                raise
        return {"assets": confirmed["assets"], "derivedReceptionId": derived["id"], **manifest["facts"]}

    def run(self, args):
        self.check_space()
        started = time.monotonic()
        log_path = getattr(self.execution, "log_path", self.root / "process.log")
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("wb") as log:
            process = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
            try:
                while process.poll() is None:
                    self.check_space()
                    if time.monotonic() - started > self.command_timeout:
                        raise subprocess.TimeoutExpired(args, self.command_timeout)
                    try:
                        process.wait(timeout=0.2)
                    except subprocess.TimeoutExpired:
                        pass
                if process.returncode:
                    raise subprocess.CalledProcessError(process.returncode, args)
                self.check_space()
            finally:
                if process.poll() is None:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                process.wait()

    def video_rate(self, path):
        output = subprocess.check_output([self.ffprobe, "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=avg_frame_rate", "-of", "json", str(path)], timeout=30)
        rate = json.loads(output)["streams"][0]["avg_frame_rate"]
        if not re.fullmatch(r"[1-9][0-9]*/[1-9][0-9]*", rate):
            raise ValueError("invalid recording video rate")
        return rate

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


class BoundedHTTPServer(ThreadingHTTPServer):
    """Slow clients cannot create an unbounded number of request threads."""
    daemon_threads = True

    def __init__(self, address, handler):
        self.slots = threading.BoundedSemaphore(16)
        super().__init__(address, handler)

    def process_request(self, sock, address):
        if not self.slots.acquire(blocking=False):
            try:
                sock.settimeout(1)
                sock.sendall(b"HTTP/1.0 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n")
            finally:
                self.shutdown_request(sock)
            return
        try:
            super().process_request(sock, address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, sock, address):
        try:
            super().process_request_thread(sock, address)
        finally:
            self.slots.release()


def handler_for(worker, token):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, *args):
            pass

        def respond(self, status, value):
            raw = json.dumps(value).encode(); self.send_response(status)
            self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store"); self.end_headers(); self.wfile.write(raw)

        def authenticated(self):
            values = self.headers.get_all("Authorization", [])
            return len(values) == 1 and hmac.compare_digest(values[0].encode("utf-8"), ("Bearer " + token).encode("utf-8"))

        def tenant(self):
            values = self.headers.get_all("X-Tenant-Id", [])
            if len(values) > 1:
                raise ValueError("ambiguous tenant")
            return worker.tenant(values[0] if values else None)

        def do_GET(self):
            if not self.authenticated():
                return self.respond(401, {})
            match = re.fullmatch(r"/jobs/(" + JOB_ID + ")", self.path)
            try:
                tenant = self.tenant()
                if self.path == "/health/live":
                    return self.respond(200, {"status": "UP"})
                if self.path == "/health/ready":
                    result = worker.readiness(tenant)
                    return self.respond(200 if result['status'] == 'UP' else 503, result)
                if self.path == "/metrics":
                    return self.respond(200, worker.metrics(tenant))
                if self.path == "/capabilities":
                    return self.respond(200, {"schemaVersion": 1, "tenantScoped": True, "tenantId": tenant})
                if not match:
                    return self.respond(404, {})
                self.respond(200, worker.get(match[1], tenant))
            except KeyError:
                self.respond(404, {})
            except PermissionError:
                self.respond(403, {})
            except ValueError:
                self.respond(400, {})
            except sqlite3.Error:
                self.respond(503, {"error": "DatabaseUnavailable"})

        def do_POST(self):
            if not self.authenticated():
                return self.respond(401, {})
            try:
                tenant = self.tenant()
                lengths = self.headers.get_all("Content-Length", [])
                if len(lengths) != 1 or self.headers.get("Transfer-Encoding"):
                    raise ValueError("ambiguous request length")
                size = int(lengths[0])
                retry_match = re.fullmatch(r"/jobs/(" + JOB_ID + ")/retry", self.path)
                if (self.path != "/jobs" and not retry_match) or not 0 < size <= 2048:
                    return self.respond(400, {})
                body = json.loads(self.rfile.read(size))
                if retry_match:
                    if body != {}:
                        raise ValueError("retry accepts only an empty object")
                    return self.respond(200, worker.retry(retry_match[1], tenant))
                if not isinstance(body, dict) or set(body) != {"receptionId", "video"}:
                    raise ValueError("invalid job fields")
                self.respond(200, worker.submit(body["receptionId"], body["video"], tenant))
            except (CapacityExceeded, WorkerStopping):
                self.respond(503, {"error": "CapacityExceeded"})
            except PermissionError:
                self.respond(403, {})
            except KeyError:
                self.respond(404, {})
            except error.HTTPError as exc:
                status = 404 if exc.code in (403, 404) else 503
                exc.close()
                self.respond(status, {})
            except (error.URLError, TimeoutError):
                self.respond(503, {})
            except (ValueError, TypeError):
                self.respond(400, {})
            except sqlite3.Error:
                self.respond(503, {"error": "DatabaseUnavailable"})

    return Handler


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
    scopes = os.environ.get("FILESERVER_ALLOWED_TENANTS")
    worker = Worker(root, Files(os.environ["FILESERVER_URL"], os.environ["FILESERVER_TENANT"], os.environ["FILESERVER_RECEPTION_TOKEN"]),
                    allowed_tenants=None if scopes is None else [scope.strip() for scope in scopes.split(",")],
                    max_pending=int(os.environ.get("MEDIA_WORKER_MAX_PENDING", "128")),
                    max_attempts=int(os.environ.get("MEDIA_WORKER_MAX_ATTEMPTS", "5")),
                    retry_seconds=int(os.environ.get("MEDIA_WORKER_RETRY_SECONDS", "30")),
                    max_bytes=int(os.environ.get("MEDIA_WORKER_MAX_INPUT_BYTES", str(16 * 1024 ** 3))),
                    max_work_bytes=int(os.environ.get("MEDIA_WORKER_MAX_WORK_BYTES", str(32 * 1024 ** 3))),
                    min_free_bytes=int(os.environ.get("MEDIA_WORKER_MIN_FREE_BYTES", str(256 * 1024 ** 2))),
                    command_timeout=int(os.environ.get("MEDIA_WORKER_COMMAND_TIMEOUT", "600")),
                    job_timeout=int(os.environ.get("MEDIA_WORKER_JOB_TIMEOUT", "3600")))

    server = BoundedHTTPServer(("127.0.0.1", args.port), handler_for(worker, token))
    server.timeout = 0.5
    def stop(signum, frame):
        worker.stop_event.set()
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    worker.consumer_thread = threading.Thread(target=worker.consume, daemon=True)
    worker.consumer_thread.start()
    worker.emit('worker_started', schemaVersion=1)
    try:
        while not worker.stop_event.is_set():
            server.handle_request()
    finally:
        worker.stop_event.set()
        server.server_close()
        worker.consumer_thread.join(timeout=65)
        worker.emit('worker_stopped', consumerStopped=not worker.consumer_thread.is_alive())



if __name__ == "__main__":
    main()
