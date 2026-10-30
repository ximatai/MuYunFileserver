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
print(json.dumps({"fileServerEndpoint": base}), flush=True)
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

current_tenant = "tenant-seaweed"
completed = []

def api(method, path, body=None, tenant=None):
    raw = json.dumps(body).encode() if body is not None else None
    headers = {"Authorization": "Bearer " + worker_token, "Content-Type": "application/json"}
    scope = current_tenant if tenant is None else tenant
    if scope:
        headers["X-Tenant-Id"] = scope
    req = request.Request(worker_url + path, data=raw, method=method, headers=headers)
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
    def rejected(method, path, expected, body=None, tenant=None):
        try:
            api(method, path, body, tenant)
            raise AssertionError('unexpectedly accepted request')
        except error.HTTPError as exc:
            assert exc.code == expected, exc.code
            exc.close()
    rejected('GET', '/capabilities', 400, tenant='')
    assert api('GET', '/capabilities', tenant='new-business-tenant')['tenantId'] == 'new-business-tenant'
    rejected('GET', '/capabilities', 400, tenant='../outside')
    for current_tenant, video in (("tenant-seaweed", False), ("tenant-reception-b", False),
                                  ("tenant-seaweed", True), ("tenant-reception-b", True)):
        files = module.Files(base, current_tenant, "reception-test-service-secret-only")
        other_tenant = "tenant-reception-b" if current_tenant == "tenant-seaweed" else "tenant-seaweed"
        capability = api('GET', '/capabilities')
        declarations=json.loads(Path('contracts/components.json').read_text())['components']
        assert declarations['fileserver']['provides']['fileReception']==[1]
        assert declarations['media-worker']['requires']['fileReception']==[1]
        assert declarations['media-worker']['provides']['workerJobs']==[capability['schemaVersion']]==[1]
        assert capability['tenantScoped'] and capability['tenantId'] == current_tenant
        source = directory / (current_tenant + ("-video-source" if video else "-audio-source")); source.mkdir()
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
        rejected('POST', '/jobs', 404, {'receptionId': task['id'], 'video': video}, tenant=other_tenant)
        api("POST", "/jobs", {"receptionId": task["id"], "video": video})
        deadline = time.monotonic() + 30
        while True:
            result = api("GET", "/jobs/" + task["id"])
            if result["state"] in ("READY", "FAILED") or time.monotonic() > deadline:
                break
            time.sleep(0.1)
        assert result["state"] == "READY", result
        assert result["tenantId"] == current_tenant
        rejected("GET", "/jobs/" + task["id"], 404, tenant=other_tenant)
        completed.append((current_tenant, task["id"], result))
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
            url = base+"/api/v1/public/files/"+asset+"/view/content/"+token(current_tenant,now+300)
            with request.urlopen(request.Request(url, headers={"Range":"bytes=0-31"}),timeout=5) as response:
                assert response.status == 206 and response.headers["Content-Range"].startswith("bytes 0-31/")
                assert len(response.read()) == 32
                assert response.headers["Content-Type"].startswith(("audio/", "video/")), response.headers
            for bad in (token("other-tenant",now+300), token(current_tenant,now-60)):
                try:
                    request.urlopen(base+"/api/v1/public/files/"+asset+"/view/content/"+bad,timeout=5)
                    raise AssertionError("invalid playback capability was accepted")
                except error.HTTPError as exc:
                    assert exc.code in (401,403,404), exc.code
                    exc.close()
        downloaded = directory / (current_tenant + ("-video-audio.ogg" if video else "-audio-only.ogg"))
        files.download(result["assets"]["audio.ogg"], downloaded, 10000000)
        assert worker.probe(downloaded)["durationMillis"] > 2400
        if video:
            movie = directory / (current_tenant + "-replay.mp4")
            files.download(result["assets"]["video.mp4"], movie, 10000000)
            subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-i", str(movie), "-f", "null", "-"], check=True, timeout=30)
        print(json.dumps({"tenant": current_tenant, "profile": "audio-video" if video else "audio", "state": result["state"], "assets": len(result["assets"]), "durationMillis": result["durationMillis"]}))

    # Restart from the same queue with a changed default; explicit frozen tenant must win.
    process.terminate()
    process.wait(timeout=10)
    environment['FILESERVER_TENANT'] = 'tenant-reception-b'
    process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('worker.py')), '--port', str(port),
                                '--root', str(directory / 'queue')], env=environment, stdout=log, stderr=log)
    deadline = time.monotonic() + 15
    while True:
        try:
            assert api('GET', '/health/ready', tenant='tenant-seaweed')['status'] == 'UP'
            break
        except (error.URLError, AssertionError):
            if process.poll() is not None or time.monotonic() > deadline:
                raise AssertionError('restarted worker did not become ready')
            time.sleep(0.1)
    for tenant, reception, expected in completed:
        assert api('GET', '/jobs/' + reception, tenant=tenant) == expected
        for asset in expected['assets'].values():
            foreign = module.Files(base, 'tenant-reception-b' if tenant == 'tenant-seaweed' else 'tenant-seaweed',
                                   'reception-test-service-secret-only')
            try:
                foreign.call('GET', '/api/v1/files/' + asset)
                raise AssertionError('foreign derived asset readable')
            except error.HTTPError as exc:
                assert exc.code == 403, exc.code
                exc.close()
    assert api('GET', '/metrics', tenant='tenant-seaweed')['states']['READY'] == 2
    assert api('GET', '/metrics', tenant='tenant-reception-b')['states']['READY'] == 2
    print(json.dumps({'restart': 'passed', 'completed': len(completed)}))

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
    process.wait(timeout=10)
    log.close()
