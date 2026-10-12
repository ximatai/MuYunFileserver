"""Fault tests; no device, vendor account or running FileServer required."""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from urllib import error
from worker import Worker

RAW = "11111111-1111-4111-8111-111111111111"
DERIVED = "22222222-2222-4222-8222-222222222222"


class BusyFiles:
    def __init__(self):
        self.state, self.uploads = "CONFIRMING", 0
    def call(self, method, path, body=None, binary=False):
        if method == "PUT":
            self.uploads += 1
            raise AssertionError("must not upload over a confirming reception")
        if path.endswith("/confirm"):
            if self.state != "READY":
                raise error.HTTPError("http://localhost/confirm", 409, "busy", {}, io.BytesIO())
            return {"state": "READY", "assets": {"audio.ogg": "stable-audio"}}
        if path.endswith(RAW):
            return {"state": "READY", "assets": {"index.m3u8": "raw-index"}}
        return {"id": DERIVED, "state": self.state}


class WorkerFaultTest(unittest.TestCase):
    def test_video_fragments_with_reset_transport_counters_preserve_audio_and_video(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            for number in range(2):
                subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi", "-i",
                    "testsrc2=size=160x90:rate=10", "-f", "lavfi", "-i",
                    f"sine=frequency={600 + number * 200}:sample_rate=48000", "-t", "2",
                    "-c:v", "libx264", "-g", "20", "-c:a", "aac", "-f", "mpegts",
                    str(source / f"segment{number}.ts")], check=True, timeout=20)
            (source / "index.m3u8").write_text("#EXTM3U\n#EXT-X-TARGETDURATION:2\n"
                "#EXTINF:2,\nsegment0.ts\n#EXTINF:2,\nsegment1.ts\n#EXT-X-ENDLIST\n")
            outputs = {}
            class SourceFiles:
                def call(self, method, path, body=None, binary=False):
                    if method == "PUT":
                        outputs[path.rsplit("/", 1)[1]] = body.read()
                        return {}
                    if path.endswith("/confirm"):
                        return {"state": "READY", "assets": {name: name for name in outputs}}
                    if path.endswith(RAW):
                        return {"state": "READY", "assets": {p.name: p.name for p in source.iterdir()}}
                    return {"id": DERIVED, "state": "OPEN"}
                def download(self, asset, destination, remaining):
                    payload = (source / asset).read_bytes()
                    destination.write_bytes(payload)
                    return len(payload)
            worker = Worker(root / "worker", SourceFiles())
            worker.submit(RAW, True)
            worker.process_next()
            result = worker.get(RAW)
            self.assertEqual("READY", result["state"], result)
            self.assertEqual({"audio.ogg", "video.mp4"}, set(outputs))
            self.assertGreater(result["durationMillis"], 3800)
            self.assertLess(result["durationMillis"], 4500)
            movie = root / "replay.mp4"
            movie.write_bytes(outputs["video.mp4"])
            subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-xerror", "-i", str(movie),
                "-f", "null", "-"], check=True, timeout=20)
            facts = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-count_frames",
                "-show_entries", "stream=codec_name,nb_read_frames", "-of", "json", str(movie)]))
            video = next(s for s in facts["streams"] if s["codec_name"] == "h264")
            self.assertEqual(40, int(video["nb_read_frames"]))
            self.assertTrue(any(s["codec_name"] == "aac" for s in facts["streams"]))

    def test_confirmation_timeout_and_restart_eventually_reuse_same_assets(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / RAW
            directory.mkdir()
            (directory / "audio.ogg").write_bytes(b"frozen-output")
            (directory / "derived-manifest.json").write_text(json.dumps({
                "objects": [{"key": "audio.ogg", "filename": "audio.ogg", "sizeBytes": 13,
                             "sha256": hashlib.sha256(b"frozen-output").hexdigest()}],
                "facts": {"durationMillis": 1000}}))
            files = BusyFiles()
            worker = Worker(root, files)
            worker.submit(RAW, False)
            worker.process_next()
            self.assertEqual("QUEUED", worker.get(RAW)["state"])
            self.assertEqual(0, files.uploads)
            # Restart while confirmation is in flight, then emulate its successful completion.
            worker = Worker(root, files)
            files.state = "READY"
            with worker.db() as db:
                db.execute("update jobs set updated=0 where id=?", (RAW,))
            worker.process_next()
            self.assertEqual("READY", worker.get(RAW)["state"])
            self.assertEqual("stable-audio", worker.get(RAW)["assets"]["audio.ogg"])
            self.assertEqual(worker.get(RAW), worker.submit(RAW, False))
            self.assertEqual(0, files.uploads)

    def test_put_racing_with_confirmation_is_retryable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / RAW
            directory.mkdir()
            (directory / "audio.ogg").write_bytes(b"frozen")
            (directory / "derived-manifest.json").write_text(json.dumps({
                "objects": [{"key": "audio.ogg"}], "facts": {}}))
            class RacingFiles(BusyFiles):
                def __init__(self):
                    super().__init__()
                    self.state = "OPEN"
                def call(self, method, path, body=None, binary=False):
                    if method == "PUT":
                        self.state = "CONFIRMING"
                        raise error.HTTPError("http://localhost/put", 409, "busy", {}, io.BytesIO())
                    return super().call(method, path, body, binary)
            worker = Worker(root, RacingFiles())
            worker.submit(RAW, False)
            worker.process_next()
            self.assertEqual("QUEUED", worker.get(RAW)["state"])

    def test_corrupt_source_cannot_become_ready(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi", "-i",
                            "sine=frequency=600:duration=5", "-c:a", "aac", "-f", "hls",
                            "-hls_time", "10", str(source / "index.m3u8")], check=True)
            segment = next(source.glob("*.ts"))
            data = bytearray(segment.read_bytes())
            # Corrupt AAC packet payload while leaving the TS container and index present.
            for packet in range(10, 30):
                offset = packet * 188
                data[offset + 12:offset + 188] = b"\xff" * 176
            segment.write_bytes(data)
            class SourceFiles:
                def call(self, *args, **kwargs):
                    return {"state": "READY", "assets": {p.name: p.name for p in source.iterdir()}}
                def download(self, asset, destination, remaining):
                    payload = (source / asset).read_bytes()
                    destination.write_bytes(payload)
                    return len(payload)
            worker = Worker(root / "worker", SourceFiles())
            worker.submit(RAW, False)
            worker.process_next()
            self.assertEqual("FAILED", worker.get(RAW)["state"])
            self.assertEqual("CalledProcessError", worker.get(RAW)["error"])
            self.assertTrue((worker.root / RAW / "raw" / segment.name).exists())


if __name__ == "__main__":
    unittest.main()
