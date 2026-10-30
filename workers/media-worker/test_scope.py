"""Tenant authorization, durable recovery and HTTP contract tests without external services."""
import http.client
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib import error
from worker import Files, Worker, handler_for

A, B = 'tenant-a', 'tenant-b'
RAW = '11111111-1111-4111-8111-111111111111'
OTHER = '22222222-2222-4222-8222-222222222222'
TOKEN = 'unit-test-service-token-24chars'


class ScopedFiles:
    def __init__(self, tenant=A, sources=None, calls=None):
        self.tenant = tenant
        self.sources = sources if sources is not None else {RAW: A, OTHER: B}
        self.calls = calls if calls is not None else []

    def for_tenant(self, tenant):
        return ScopedFiles(tenant, self.sources, self.calls)

    def call(self, method, path, body=None, binary=False):
        self.calls.append((self.tenant, method, path))
        if self.sources.get(path.rsplit('/', 1)[-1]) != self.tenant:
            raise error.HTTPError('http://localhost/redacted', 403, 'forbidden', {}, io.BytesIO())
        return {'state': 'READY', 'assets': {'index.m3u8': 'source-asset'}}


class ScopeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.files = ScopedFiles()
        self.worker = Worker(self.root, self.files)

    def test_source_ownership_checked_before_claiming_task_id(self):
        with self.assertRaises(error.HTTPError):
            self.worker.submit(RAW, False, B)
        with self.worker.db() as db:
            self.assertEqual(0, db.execute('select count(*) from jobs').fetchone()[0])
        self.assertEqual(A, self.worker.submit(RAW, False, A)['tenantId'])
        with self.assertRaises(KeyError):
            self.worker.get(RAW, B)
        with self.assertRaises(KeyError):
            self.worker.submit(RAW, False, B)

    def test_same_job_profile_is_idempotent_even_after_source_unavailable(self):
        first = self.worker.submit(RAW, False, A)
        self.files.sources.clear()
        self.assertEqual(first, self.worker.submit(RAW, False, A))
        with self.assertRaises(ValueError):
            self.worker.submit(RAW, True, A)

    def test_invalid_types_and_ids_do_not_reserve_rows(self):
        for reception, video in [(None, False), ('-' * 36, False), (RAW, 1), ([], False)]:
            with self.assertRaises(ValueError):
                self.worker.submit(reception, video, A)
        with self.worker.db() as db:
            self.assertEqual(0, db.execute('select count(*) from jobs').fetchone()[0])

    def test_restart_uses_persisted_scope_not_new_default(self):
        self.worker.submit(RAW, False, A)
        self.worker.submit(OTHER, True, B)
        seen = []
        class RecoverWorker(Worker):
            def process(self, reception, video, tenant=None):
                files = self.files_for(tenant)
                files.call('GET', '/api/v1/internal/receptions/' + reception)
                seen.append((reception, files.tenant, video))
                return {'assets': {}, 'tenantId': 'cannot-override-scope'}
        recovered = RecoverWorker(self.root, self.files.for_tenant(B))
        self.assertTrue(recovered.process_next())
        self.assertTrue(recovered.process_next())
        self.assertEqual([(RAW, A, False), (OTHER, B, True)], seen)
        self.assertEqual(A, recovered.get(RAW, A)['tenantId'])
        self.assertEqual(B, recovered.get(OTHER, B)['tenantId'])

    def test_new_default_does_not_hide_or_reassign_persisted_business_jobs(self):
        self.worker.submit(RAW, False, A)
        seen = []
        class Recovered(Worker):
            def process(self, reception, video, tenant=None):
                seen.append(tenant)
                return {'assets': {}}
        recovered = Recovered(self.root, self.files.for_tenant(B))
        self.assertTrue(recovered.process_next())
        self.assertEqual([A], seen)
        self.assertEqual(A, recovered.get(RAW, A)['tenantId'])
        with self.assertRaises(KeyError):
            recovered.get(RAW, B)

    def test_legacy_migration_freezes_original_tenant_and_preserves_result(self):
        legacy = self.root / 'legacy'
        legacy.mkdir()
        with sqlite3.connect(legacy / 'jobs.db') as db:
            db.execute('create table jobs(id text primary key,video integer,state text,result text,error text,updated real)')
            db.execute('insert into jobs values(?,0,\'READY\',?,null,0)', (RAW, json.dumps({'assets': {'audio.ogg': 'stable'}})))
        Worker(legacy, self.files)
        changed = Worker(legacy, self.files.for_tenant(B))
        self.assertEqual({'audio.ogg': 'stable'}, changed.get(RAW, A)['assets'])
        with self.assertRaises(KeyError):
            changed.get(RAW, B)

    def test_actual_processing_keeps_source_and_derived_calls_in_same_scope(self):
        calls = []
        class ProcessingFiles(ScopedFiles):
            def for_tenant(self, tenant):
                return ProcessingFiles(tenant)
            def call(self, method, path, body=None, binary=False):
                calls.append((self.tenant, method, path))
                if method == 'PUT':
                    self.assert_payload = body.read()
                    return {}
                if path.endswith('/confirm'):
                    return {'state': 'READY', 'assets': {'audio.ogg': self.tenant + '-asset'}}
                if method == 'POST':
                    return {'id': OTHER, 'state': 'OPEN'}
                return {'state': 'READY', 'assets': {'index.m3u8': 'source'}}
        worker = Worker(self.root, ProcessingFiles(A))
        worker.submit(RAW, False, B)
        directory = self.root / RAW
        directory.mkdir()
        (directory / 'audio.ogg').write_bytes(b'frozen')
        (directory / 'derived-manifest.json').write_text(json.dumps({
            'objects': [{'key': 'audio.ogg'}], 'facts': {'durationMillis': 1000}}))
        worker.process_next()
        result = worker.get(RAW, B)
        self.assertEqual('READY', result['state'])
        self.assertEqual({'audio.ogg': B + '-asset'}, result['assets'])
        self.assertTrue(any(method == 'PUT' for _, method, _ in calls))
        self.assertEqual({B}, {tenant for tenant, _, _ in calls})

    def test_tenant_format_matches_reception_service(self):
        for bad in ['tenant.dot', 'tenant:colon', 'a' * 65, '', '../x']:
            with self.assertRaises(ValueError):
                Files('http://127.0.0.1', bad, TOKEN)
        with self.assertRaises(ValueError):
            Worker(self.root, ScopedFiles('bad.scope'))

    def test_real_http_authorization_scope_and_body_errors(self):
        server = ThreadingHTTPServer(('127.0.0.1', 0), handler_for(self.worker, TOKEN))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        def call(method, path, body=None, tenant=A, authorization=TOKEN, duplicate=None):
            client = http.client.HTTPConnection(*server.server_address, timeout=3)
            payload = json.dumps(body).encode() if body is not None else None
            client.putrequest(method, path)
            if authorization is not None:
                client.putheader('Authorization', 'Bearer ' + authorization)
            if tenant is not None:
                client.putheader('X-Tenant-Id', tenant)
            if duplicate:
                client.putheader(*duplicate)
            if payload is not None:
                client.putheader('Content-Length', str(len(payload)))
            client.endheaders(payload)
            response = client.getresponse()
            result = response.status, json.load(response)
            client.close()
            return result
        self.assertEqual(401, call('GET', '/capabilities', authorization=None)[0])
        self.assertEqual(401, call('GET', '/health/live', authorization=None)[0])
        self.assertEqual(200, call('GET', '/health/live')[0])
        self.assertEqual(503, call('GET', '/health/ready')[0])
        self.assertEqual({}, call('GET', '/metrics')[1]['states'])
        self.assertEqual(401, call('GET', '/capabilities', authorization='wrong')[0])
        self.assertEqual(401, call('GET', '/capabilities', duplicate=('Authorization', 'Bearer ' + TOKEN))[0])
        self.assertEqual(400, call('GET', '/capabilities', tenant=None)[0])
        self.assertEqual(200, call('GET', '/capabilities', tenant='new-business-tenant')[0])
        self.assertEqual(400, call('GET', '/capabilities', tenant='../outside')[0])
        self.assertEqual(400, call('GET', '/capabilities', duplicate=('X-Tenant-Id', B))[0])
        self.assertEqual({'schemaVersion': 1, 'tenantScoped': True, 'tenantId': A, 'provides': {'workerJobs': [1]}, 'requires': {'fileReception': [1]}}, call('GET', '/capabilities')[1])
        for malformed in [{}, [], {'receptionId': RAW}, {'receptionId': None, 'video': False}]:
            self.assertEqual(400, call('POST', '/jobs', malformed)[0])
        body = {'receptionId': RAW, 'video': False}
        self.assertEqual(404, call('POST', '/jobs', body, tenant=B)[0])
        self.assertEqual(200, call('POST', '/jobs', body)[0])
        self.assertEqual(404, call('GET', '/jobs/' + RAW, tenant=B)[0])
        self.assertEqual(404, call('POST', '/jobs', body, tenant=B)[0])
        self.assertEqual(A, call('GET', '/jobs/' + RAW)[1]['tenantId'])
        self.worker.max_pending = 1
        self.assertEqual(503, call('POST', '/jobs', {'receptionId': OTHER, 'video': False}, tenant=B)[0])
        with self.worker.db() as db:
            db.execute("update jobs set state='FAILED',attempts=5 where id=?", (RAW,))
        self.assertEqual(404, call('POST', '/jobs/' + RAW + '/retry', {}, tenant=B)[0])
        self.assertEqual(400, call('POST', '/jobs/' + RAW + '/retry', {'video': True})[0])
        self.assertEqual(0, call('POST', '/jobs/' + RAW + '/retry', {})[1]['attempts'])

    def test_legacy_single_scope_can_omit_header(self):
        single = Worker(self.root, self.files)
        self.assertEqual(A, single.submit(RAW, False)['tenantId'])


if __name__ == '__main__':
    unittest.main()
