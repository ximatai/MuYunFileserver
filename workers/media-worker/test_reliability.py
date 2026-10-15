"""Queue budgets, recovery and subprocess resource guards."""
import json
from concurrent.futures import ThreadPoolExecutor
import threading
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib import error
from worker import Worker, CapacityExceeded, JobTimeout
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
