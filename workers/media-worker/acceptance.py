"""Executed by the isolated SeaweedFS/Quarkus acceptance test; never contacts real business services."""
import hashlib
import hmac
import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid
import socket
import time
from urllib import request, error

spec = importlib.util.spec_from_file_location("media_worker", Path(__file__).with_name("worker.py"))
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
base, directory = sys.argv[1].rstrip("/"), Path(sys.argv[2])
files = module.Files(base, "tenant-seaweed", "reception-test-service-secret-only")
worker = module.Worker(directory / "helpers", files)
with socket.socket() as reservation:
    reservation.bind(("127.0.0.1", 0))
    port = reservation.getsockname()[1]
worker_token = "worker-acceptance-token-only-24chars"
environment = dict(os.environ, MEDIA_WORKER_TOKEN=worker_token, FILESERVER_URL=base,
                   FILESERVER_TENANT="tenant-seaweed", FILESERVER_RECEPTION_TOKEN="reception-test-service-secret-only")
log = (directory / "worker.log").open("wb")
process = subprocess.Popen([sys.executable, str(Path(__file__).with_name("worker.py")), "--port", str(port),
                            "--root", str(directory / "queue")], env=environment, stdout=log, stderr=log)
worker_url = "http://127.0.0.1:" + str(port)

def api(method, path, body=None):
    raw = json.dumps(body).encode() if body is not None else None
    req = request.Request(worker_url + path, data=raw, method=method, headers={"Authorization": "Bearer " + worker_token, "Content-Type": "application/json"})
    with request.urlopen(req, timeout=5) as response:
        return json.load(response)

try:
    deadline = time.monotonic() + 15
    while True:
        try:
            request.urlopen(worker_url + "/jobs/missing", timeout=1)
        except error.HTTPError as exc:
            assert exc.code == 401
            break
        except error.URLError:
            if process.poll() is not None or time.monotonic() > deadline:
                raise AssertionError("worker HTTP service did not start")
            time.sleep(0.1)
    for video in (False, True):
        source = directory / ("video-source" if video else "audio-source"); source.mkdir()
        args = ["ffmpeg", "-nostdin", "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=16000"]
        if video:
            args += ["-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15", "-c:v", "libx264", "-preset", "ultrafast"]
        args += ["-t", "2.5", "-c:a", "aac", "-f", "hls", "-hls_time", "1", "-hls_playlist_type", "vod", str(source / "index.m3u8")]
        subprocess.run(args, check=True, timeout=30)
        task = files.call("POST", "/api/v1/internal/receptions", {"idempotencyKey": str(uuid.uuid4()), "maxObjects": 32, "maxBytes": 10000000, "expiresInSeconds": 3600})
        manifest = []
        for path in sorted(source.iterdir()):
            with path.open("rb") as stream:
                files.call("PUT", "/api/v1/internal/receptions/" + task["id"] + "/objects/" + path.name, stream, binary=True)
            manifest.append(worker.entry(path))
        files.call("POST", "/api/v1/internal/receptions/" + task["id"] + "/confirm", {"objects": manifest})
        api("POST", "/jobs", {"receptionId": task["id"], "video": video})
        deadline = time.monotonic() + 30
        while True:
            result = api("GET", "/jobs/" + task["id"])
            if result["state"] in ("READY", "FAILED") or time.monotonic() > deadline:
                break
            time.sleep(0.1)
        assert result["state"] == "READY", (result, (directory / "queue" / "process.log").read_text())
        assert result["durationMillis"] > 2400 and result["channels"] == 1, result
        assert ("video.mp4" in result["assets"]) == video, result
        assert api("POST", "/jobs", {"receptionId": task["id"], "video": video}) == result
        # Same viewer capability and inline Range route used by the MR history player.
        for asset in result["assets"].values():
            metadata = files.call("GET", "/api/v1/files/"+asset)
            assert metadata["mimeType"].startswith(("audio/", "video/")), metadata
            now = int(time.time())
            def token(tenant, expires):
                payload = json.dumps({"iss":"seaweed-it","sub":"history-reviewer","purpose":"viewer",
                    "tenant_id":tenant,"file_id":asset,"iat":now,"exp":expires,"jti":"playback-test"}, separators=(",",":")).encode()
                encode = lambda raw: base64.urlsafe_b64encode(raw).decode().rstrip("=")
                return encode(payload)+"."+encode(hmac.new(b"test-token-secret",payload,hashlib.sha256).digest())
            url = base+"/api/v1/public/files/"+asset+"/view/content/"+token("tenant-seaweed",now+300)
            with request.urlopen(request.Request(url, headers={"Range":"bytes=0-31"}),timeout=5) as response:
                assert response.status == 206 and response.headers["Content-Range"].startswith("bytes 0-31/")
                assert len(response.read()) == 32
                assert response.headers["Content-Type"].startswith(("audio/", "video/")), response.headers
            for bad in (token("other-tenant",now+300), token("tenant-seaweed",now-60)):
                try:
                    request.urlopen(base+"/api/v1/public/files/"+asset+"/view/content/"+bad,timeout=5)
                    raise AssertionError("invalid playback capability was accepted")
                except error.HTTPError as exc:
                    assert exc.code in (401,403,404), exc.code
                    exc.close()
        downloaded = directory / ("video-audio.ogg" if video else "audio-only.ogg")
        files.download(result["assets"]["audio.ogg"], downloaded, 10000000)
        assert worker.probe(downloaded)["durationMillis"] > 2400
        if video:
            movie = directory / "replay.mp4"
            files.download(result["assets"]["video.mp4"], movie, 10000000)
            subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-i", str(movie), "-f", "null", "-"], check=True, timeout=30)
        print(json.dumps({"profile": "audio-video" if video else "audio", "state": result["state"], "assets": len(result["assets"]), "durationMillis": result["durationMillis"]}))

    # SSRF/traversal and incomplete indexes are rejected before invoking a decoder.
    unsafe = directory / "unsafe.m3u8"
    unsafe.write_text("#EXTM3U\n#EXTINF:1,\nhttps://example.invalid/segment.ts\n#EXT-X-ENDLIST\n")
    try:
        module.validate_hls(unsafe, set())
        raise AssertionError("remote HLS URI was accepted")
    except ValueError:
        pass
finally:
    process.terminate()
    process.wait(timeout=5)
    log.close()
