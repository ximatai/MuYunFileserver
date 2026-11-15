import hashlib
from io import BytesIO
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from storage_cutover import check_and_switch


class MemoryBucket:
    def __init__(self, objects):
        self.objects = objects

    def list_keys(self, prefix):
        return {key for key in self.objects if key.startswith(prefix)}

    def open(self, key):
        if key not in self.objects:
            raise FileNotFoundError(key)
        return BytesIO(self.objects[key])


class StorageCutoverTest(unittest.TestCase):
    def setUp(self):
        workspace = tempfile.TemporaryDirectory()
        self.addCleanup(workspace.cleanup)
        self.root = Path(workspace.name)
        self.database = self.root / "files.sqlite"
        self.source = self.root / "source"
        self.target = self.root / "target"
        self.source.mkdir()
        self.target.mkdir()
        self.receptions = {
            "source-rx": {"receiving/active/index": b"recorded input"},
            "target-rx": {"receiving/active/index": b"recorded input"},
        }
        migrations = Path(__file__).resolve().parents[1] / "src/main/resources/db/migration"
        with sqlite3.connect(self.database) as db:
            for migration in sorted(migrations.glob("V*.sql"), key=lambda item: int(item.name.split("__")[0][1:])):
                db.executescript(migration.read_text())
            db.executemany("insert into storage_location_binding values (?,?,?)", [
                ("source", f"local:{self.source}", "ASSET"),
                ("target", f"local:{self.target}", "ASSET"),
                ("source-rx", "http://source.example:9000/recordings-a", "RECEPTION"),
                ("target-rx", "http://target.example:8333/recordings-b", "RECEPTION"),
            ])
            self.insert_file(db, "file-id", "original", b"original bytes", "source")
            db.execute("""
                insert into file_preview_artifact(file_id,artifact_key,tenant_id,source_kind,status,target_mime_type,
                    storage_provider,storage_key,size_bytes,sha256,generated_at,last_accessed_at,storage_location_id)
                values ('file-id','view_pdf','tenant','GENERATED_PDF','READY','application/pdf',
                    'local','preview',?,?, '2026-01-01','2026-01-01','source')
                """, (len(b"pdf bytes"), hashlib.sha256(b"pdf bytes").hexdigest()))
            db.execute("""
                insert into file_reception(id,tenant_id,idempotency_key,prefix,state,max_objects,max_bytes,
                    expires_at,bucket,ttl_seconds,storage_location_id)
                values ('recording','tenant','task-key','receiving/active/','OPEN',5,1024,2000000000000,
                    'recordings-a',3600,'source-rx')
                """)
            db.execute("""
                insert into file_reception_staged values ('unpublished','recording','lease','late','source')
                """)
        (self.source / "preview").write_bytes(b"pdf bytes")
        shutil.copytree(self.source, self.target, dirs_exist_ok=True)
        self.moves = [("source", "target"), ("source-rx", "target-rx")]
        fake_s3 = patch("storage_cutover.S3Objects", side_effect=lambda item, _: MemoryBucket(self.receptions[item.id]))
        fake_s3.start()
        self.addCleanup(fake_s3.stop)

    def insert_file(self, db, file_id, key, data, location):
        db.execute("""
            insert into file_metadata(id,tenant_id,original_filename,mime_type,size_bytes,sha256,storage_provider,
                storage_key,status,uploaded_by,uploaded_at,storage_location_id)
            values (?,'tenant','filename','text/plain',?,?,'local',?,'ACTIVE','tester','2026-01-01',?)
            """, (file_id, len(data), hashlib.sha256(data).hexdigest(), key, location))
        (self.root / location / key).write_bytes(data)

    def rows(self, sql):
        with sqlite3.connect(self.database) as db:
            return db.execute(sql).fetchall()

    def test_cutover_can_be_reversed_without_losing_post_cutover_writes(self):
        preview = check_and_switch(self.database, self.moves, {})
        self.assertEqual([2, 1], [item["objectsVerified"] for item in preview])
        self.assertEqual([("source",)], self.rows("select storage_location_id from file_metadata"))
        result = check_and_switch(self.database, self.moves, {}, apply=True)
        self.assertEqual([3, 1], [item["referencesUpdated"] for item in result])
        self.assertEqual([("file-id", "target")], self.rows("select id,storage_location_id from file_metadata"))
        self.assertEqual([("target",)], self.rows("select storage_location_id from file_preview_artifact"))
        self.assertEqual([("target",)], self.rows("select storage_location_id from file_reception_staged"))
        self.assertEqual([("recording", "recordings-b", "OPEN", "target-rx")],
                         self.rows("select id,bucket,state,storage_location_id from file_reception"))

        with sqlite3.connect(self.database) as db:
            self.insert_file(db, "after-cutover", "new-object", b"new bytes", "target")
        rollback = [(target, source) for source, target in self.moves]
        with self.assertRaisesRegex(ValueError, "between locations"):
            check_and_switch(self.database, rollback, {}, apply=True)
        self.assertEqual([("target",), ("target",)], self.rows("select storage_location_id from file_metadata"))
        shutil.copy2(self.target / "new-object", self.source / "new-object")
        check_and_switch(self.database, rollback, {}, apply=True)
        self.assertEqual([("after-cutover", "source"), ("file-id", "source")],
                         self.rows("select id,storage_location_id from file_metadata order by id"))
        self.assertEqual([("recording", "recordings-a", "OPEN", "source-rx")],
                         self.rows("select id,bucket,state,storage_location_id from file_reception"))
        self.assertEqual(b"new bytes", (self.target / "new-object").read_bytes())

    def test_later_mismatch_rolls_back_prior_location_updates(self):
        self.receptions["target-rx"]["receiving/active/extra"] = b"unexpected"
        with self.assertRaisesRegex(ValueError, "prefix contents differ"):
            check_and_switch(self.database, self.moves, {}, apply=True)
        self.assertEqual([("source",)], self.rows("select storage_location_id from file_metadata"))

    def test_refuses_existing_target_reference_even_if_bytes_match(self):
        with sqlite3.connect(self.database) as db:
            self.insert_file(db, "target-file", "original", b"original bytes", "target")
        with self.assertRaisesRegex(ValueError, "already referenced"):
            check_and_switch(self.database, [("source", "target")], {}, preflight=True)
        with self.assertRaisesRegex(ValueError, "already referenced"):
            check_and_switch(self.database, [("source", "target")], {}, apply=True)

    def test_preflight_does_not_require_objects_to_have_been_copied(self):
        shutil.rmtree(self.target)
        self.receptions.clear()
        report = check_and_switch(self.database, self.moves, {}, preflight=True)
        self.assertEqual(["ASSET", "RECEPTION"], [item["role"] for item in report])
        self.assertEqual([("source",)], self.rows("select storage_location_id from file_metadata"))

    def test_refuses_copies_that_disagree_with_a_registered_checksum(self):
        for root in (self.source, self.target):
            (root / "original").write_bytes(b"corrupt bytes!")
        with self.assertRaisesRegex(ValueError, "recorded metadata"):
            check_and_switch(self.database, [("source", "target")], {}, apply=True)

    def test_original_pdf_aliases_are_rebuilt_after_verified_cutover(self):
        with sqlite3.connect(self.database) as db:
            self.insert_file(db, "native-pdf", "native.pdf", b"pdf bytes", "source")
            db.executemany("""
                insert into file_preview_artifact(file_id,artifact_key,tenant_id,source_kind,status,target_mime_type,
                    storage_provider,storage_key,generated_at,last_accessed_at,storage_location_id)
                values (?,'view_pdf','tenant','ORIGINAL_PDF','READY','application/pdf',
                    'local',?,'2026-01-01','2026-01-01','source')
                """, [("native-pdf", "native.pdf"), ("deleted-pdf", "deleted.pdf")])
        with self.assertRaisesRegex(ValueError, "between locations"):
            check_and_switch(self.database, [("source", "target")], {}, apply=True)
        self.assertEqual(3, len(self.rows("select file_id from file_preview_artifact")))
        shutil.copy2(self.source / "native.pdf", self.target / "native.pdf")
        check_and_switch(self.database, [("source", "target")], {}, apply=True)
        self.assertEqual([("native-pdf", "target")], self.rows(
            "select id,storage_location_id from file_metadata where id='native-pdf'"))
        self.assertEqual([("file-id", "GENERATED_PDF", "target")], self.rows(
            "select file_id,source_kind,storage_location_id from file_preview_artifact"))

    def test_rejects_local_storage_keys_that_escape_the_root(self):
        with sqlite3.connect(self.database) as db:
            db.execute("update file_metadata set storage_key='../outside'")
        with self.assertRaisesRegex(ValueError, "escapes local storage"):
            check_and_switch(self.database, [("source", "target")], {}, apply=True)


if __name__ == "__main__":
    unittest.main()
