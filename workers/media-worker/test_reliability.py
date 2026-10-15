"""Queue budgets, recovery and subprocess resource guards."""
import json
import sqlite3
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib import error
from worker import Worker, Files, CapacityExceeded, JobTimeout
from test_scope import ScopedFiles, A, B, RAW, OTHER


class ReliabilityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_transient_failures_exhaust_budget_and_explicit_retry_preserves_scope(self):
        class Failing(Worker):
            def process(self, *args):
                raise error.URLError('do-not-persist-sensitive-upstream-text')
        worker = Failing(self.root, ScopedFiles(), max_attempts=2, allowed_tenants=[A, B])
        worker.submit(RAW, False, A)
        worker.process_next()
        self.assertEqual('QUEUED', worker.get(RAW, A)['state'])
        with worker.db() as db:
            db.execute('update jobs set updated=0')
        worker.process_next()
        result = worker.get(RAW, A)
        self.assertEqual(('FAILED', 2, 'RetryExhausted'), (result['state'], result['attempts'], result['error']))
        self.assertEqual(result, worker.submit(RAW, False, A))
        with self.assertRaises(KeyError):
            worker.retry(RAW, B)
        self.assertEqual(0, worker.retry(RAW, A)['attempts'])
        worker.process_next()
        # Repeated manual retry while queued must not reset an active retry budget.
        self.assertEqual(1, worker.retry(RAW, A)['attempts'])

    def test_crash_loop_does_not_bypass_persisted_attempt_limit(self):
        worker = Worker(self.root, ScopedFiles(), max_attempts=2)
        worker.submit(RAW, False)
        with worker.db() as db:
            db.execute("update jobs set state='PROCESSING',attempts=2,updated=0")
        recovered = Worker(self.root, ScopedFiles(), max_attempts=2)
        self.assertTrue(recovered.process_next())
        self.assertEqual('RetryExhausted', recovered.get(RAW)['error'])
        self.assertFalse(recovered.process_next())

    def test_queue_capacity_does_not_break_existing_task_idempotency(self):
        files = ScopedFiles(sources={RAW: A, OTHER: A})
        worker = Worker(self.root, files, max_pending=1)
        first = worker.submit(RAW, False)
        with self.assertRaises(CapacityExceeded):
            worker.submit(OTHER, False)
        self.assertEqual(first, worker.submit(RAW, False))
        with worker.db() as db:
            self.assertEqual(1, db.execute('select count(*) from jobs').fetchone()[0])

    def test_concurrent_submissions_do_not_exceed_queue_limit(self):
        barrier = threading.Barrier(2)
        class RacingFiles(ScopedFiles):
            def call(self, *args, **kwargs):
                result = super().call(*args, **kwargs)
                barrier.wait(timeout=3)
                return result
        worker = Worker(self.root, RacingFiles(sources={RAW: A, OTHER: A}), max_pending=1)
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = [executor.submit(worker.submit, reception, False) for reception in [RAW, OTHER]]
            outcomes = []
            for result in results:
                try:
                    result.result(timeout=5)
                    outcomes.append('accepted')
                except CapacityExceeded:
                    outcomes.append('full')
        self.assertCountEqual(['accepted', 'full'], outcomes)
        with worker.db() as db:
            self.assertEqual(1, db.execute('select count(*) from jobs').fetchone()[0])

    def test_storage_threshold_rejects_admission_without_reserving_task(self):
        worker = Worker(self.root, ScopedFiles(), max_work_bytes=1)
        with self.assertRaises(CapacityExceeded):
            worker.submit(RAW, False)
        with worker.db() as db:
            self.assertEqual(0, db.execute('select count(*) from jobs').fetchone()[0])

    def test_low_disk_kills_running_encoder_and_reaps_child(self):
        worker = Worker(self.root, ScopedFiles(), min_free_bytes=1)
        checks = 0
        original = worker.check_space
        def disk_full():
            nonlocal checks
            checks += 1
            if checks > 2:
                raise CapacityExceeded('full')
            original()
        with patch.object(worker, 'check_space', side_effect=disk_full):
            with self.assertRaises(CapacityExceeded):
                worker.run([sys.executable, '-c', 'import time; time.sleep(10)'])

    def test_command_timeout_does_not_leave_subprocess_running(self):
        worker = Worker(self.root, ScopedFiles(), command_timeout=1)
        with self.assertRaises(subprocess.TimeoutExpired):
            worker.run([sys.executable, '-c', 'import time; time.sleep(10)'])

    def test_job_deadline_is_separate_from_http_request_threads(self):
        worker = Worker(self.root, ScopedFiles())
        worker.execution.deadline = 0
        with self.assertRaises(JobTimeout):
            worker.check_space()
        worker.execution.deadline = float('inf')
        worker.check_space()

    def test_final_confirmation_past_deadline_preserves_manifest_and_recovers_same_asset(self):
        confirmed = threading.Event()
        delay = [True]
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def respond(self, data):
                payload = json.dumps({'data': data}).encode()
                self.send_response(200)
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            def do_GET(self):
                self.respond({'state': 'READY', 'assets': {'index.m3u8': 'raw'}})
            def do_PUT(self):
                if self.headers.get('Transfer-Encoding') == 'chunked':
                    while True:
                        size = int(self.rfile.readline().strip(), 16)
                        if not size:
                            self.rfile.readline()
                            break
                        self.rfile.read(size)
                        self.rfile.read(2)
                else:
                    self.rfile.read(int(self.headers.get('Content-Length', '0')))
                self.respond({})
            def do_POST(self):
                if self.path.endswith('/confirm'):
                    confirmed.set()
                    if delay[0]:
                        time.sleep(1.2)
                    self.respond({'state': 'READY', 'assets': {'audio.ogg': 'stable-asset'}})
                else:
                    self.respond({'id': OTHER, 'state': 'READY' if confirmed.is_set() else 'OPEN'})
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        worker = Worker(self.root, Files('http://127.0.0.1:' + str(server.server_port), A, 'test'), job_timeout=1)
        worker.submit(RAW, False)
        directory = self.root / RAW
        directory.mkdir()
        (directory / 'audio.ogg').write_bytes(b'frozen')
        manifest = directory / 'derived-manifest.json'
        manifest.write_text(json.dumps({'objects': [{'key': 'audio.ogg'}], 'facts': {}}))
        worker.process_next()
        self.assertTrue(confirmed.is_set())
        self.assertEqual(('FAILED', 'JobTimeout'), (worker.get(RAW)['state'], worker.get(RAW)['error']))
        self.assertTrue(manifest.exists())
        delay[0] = False
        worker.retry(RAW)
        worker.process_next()
        self.assertEqual('READY', worker.get(RAW)['state'])
        self.assertEqual({'audio.ogg': 'stable-asset'}, worker.get(RAW)['assets'])

    def test_graceful_stop_interrupts_encoder_and_preserves_retry_budget(self):
        started = threading.Event()
        class Stoppable(Worker):
            def process(self, *args):
                started.set()
                self.run([sys.executable, '-c', 'import time; time.sleep(10)'])
        worker = Stoppable(self.root, ScopedFiles())
        worker.submit(RAW, False)
        worker.consumer_thread = threading.Thread(target=worker.consume)
        worker.consumer_thread.start()
        self.assertTrue(started.wait(timeout=3))
        worker.stop_event.set()
        worker.consumer_thread.join(timeout=3)
        self.assertFalse(worker.consumer_thread.is_alive())
        self.assertEqual(('QUEUED', 0), (worker.get(RAW)['state'], worker.get(RAW)['attempts']))
        self.assertEqual('DOWN', worker.readiness(A)['status'])

    def test_consumer_recovers_uncertain_terminal_writes_before_reporting_healthy(self):
        failed = threading.Event()
        recovering = threading.Event()
        release = threading.Event()
        class UncertainWrites(Worker):
            failures = 0
            processes = 0
            injected = False
            def db(self):
                if self.failures:
                    self.failures -= 1
                    if not self.failures:
                        failed.set()
                    raise sqlite3.OperationalError('temporary simulated I/O failure')
                return super().db()
            def process(self, reception, video, tenant=None):
                self.processes += 1
                # A remote confirmation already exists. Its frozen manifest must
                # survive an uncertain local completion, rather than creating a new job.
                manifest = self.root / reception / 'derived-manifest.json'
                assert manifest.read_text() == 'frozen'
                if not self.injected:
                    self.injected = True
                    self.failures = 2  # READY publication and exception compensation.
                else:
                    recovering.set()
                    release.wait(timeout=5)
                return {'assets': {'audio.ogg': 'original-confirmed-asset'}}
        worker = UncertainWrites(self.root, ScopedFiles(), allowed_tenants=[A, B])
        worker.submit(RAW, False, A)
        directory = self.root / RAW
        directory.mkdir()
        (directory / 'derived-manifest.json').write_text('frozen')
        worker.consumer_thread = threading.Thread(target=worker.consume)
        worker.consumer_thread.start()
        try:
            self.assertTrue(failed.wait(timeout=3))
            self.assertTrue(recovering.wait(timeout=3))
            self.assertEqual('DOWN', worker.readiness(A)['status'])
            self.assertEqual('PROCESSING', worker.get(RAW, A)['state'])
            release.set()
            deadline = time.monotonic() + 3
            while worker.get(RAW, A)['state'] != 'READY' and time.monotonic() < deadline:
                time.sleep(0.01)
            result = worker.get(RAW, A)
            self.assertEqual(('READY', A, 2), (result['state'], result['tenantId'], result['attempts']))
            self.assertEqual({'audio.ogg': 'original-confirmed-asset'}, result['assets'])
            deadline = time.monotonic() + 3
            while worker.readiness(A)['status'] != 'UP' and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertEqual('UP', worker.readiness(A)['status'])
        finally:
            release.set()
            worker.stop_event.set()
            worker.consumer_thread.join(timeout=3)

    def test_uncertain_commit_does_not_downgrade_an_already_published_result(self):
        class AmbiguousCommit(Worker):
            uncertain_commit = False
            def db(self):
                connection = super().db()
                if not self.uncertain_commit:
                    return connection
                self.uncertain_commit = False
                @contextmanager
                def committed_but_unacknowledged():
                    with connection as db:
                        yield db
                    raise sqlite3.OperationalError('commit acknowledgement unavailable')
                return committed_but_unacknowledged()
            def process(self, *args):
                self.uncertain_commit = True
                return {'assets': {'audio.ogg': 'published-asset'}}
        worker = AmbiguousCommit(self.root, ScopedFiles())
        worker.submit(RAW, False)
        worker.process_next()
        result = worker.get(RAW)
        self.assertEqual('READY', result['state'])
        self.assertEqual({'audio.ogg': 'published-asset'}, result['assets'])
        self.assertNotIn('error', result)
        self.assertFalse(worker.process_next())

    def test_closed_stdout_does_not_turn_successful_work_into_failure(self):
        class Successful(Worker):
            def process(self, *args):
                return {'assets': {'audio.ogg': 'stable'}}
        worker = Successful(self.root, ScopedFiles())
        with patch('builtins.print', side_effect=BrokenPipeError):
            worker.submit(RAW, False)
            worker.process_next()
        self.assertEqual('READY', worker.get(RAW)['state'])

    def test_readiness_reports_dead_consumer_and_metrics_are_scoped(self):
        worker = Worker(self.root, ScopedFiles(), allowed_tenants=[A, B])
        worker.submit(RAW, False, A)
        worker.submit(OTHER, False, B)
        self.assertEqual('DOWN', worker.readiness(A)['status'])
        worker.consumer_thread = threading.current_thread()
        self.assertEqual('UP', worker.readiness(A)['status'])
        self.assertEqual({'QUEUED': 1}, worker.metrics(A)['states'])
        worker.consumer_error = 'OperationalError'
        self.assertEqual('DOWN', worker.readiness(A)['status'])
        worker.consumer_error = None
        worker.max_pending = 2
        self.assertEqual('DOWN', worker.readiness(A)['status'])

    def test_restart_reclaims_ready_residue_but_preserves_failed_evidence(self):
        worker = Worker(self.root, ScopedFiles(), allowed_tenants=[A, B])
        worker.submit(RAW, False, A)
        worker.submit(OTHER, False, B)
        for reception in [RAW, OTHER]:
            directory = self.root / reception
            directory.mkdir()
            (directory / 'derived-manifest.json').write_text('frozen')
        with worker.db() as db:
            db.execute("update jobs set state='READY',result=? where id=?", (json.dumps({'assets': {'audio.ogg': 'stable'}}), RAW))
            db.execute("update jobs set state='FAILED' where id=?", (OTHER,))
        restored = Worker(self.root, ScopedFiles(), allowed_tenants=[A, B])
        self.assertFalse((self.root / RAW).exists())
        self.assertTrue((self.root / OTHER / 'derived-manifest.json').exists())
        self.assertEqual({'audio.ogg': 'stable'}, restored.get(RAW, A)['assets'])


if __name__ == '__main__':
    unittest.main()
