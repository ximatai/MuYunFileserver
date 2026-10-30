import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from release_manifest import create, verify


class ReleaseManifestTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ['quarkus-app/quarkus-run.jar', 'workers/media-worker/worker.py', 'application.yml']:
            p = self.root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(b'fixture')

    def test_one_identity_covers_both_components(self):
        data = create(self.root, '0.27.1', 'a' * 40)
        self.assertEqual(data, verify(self.root))
        with self.assertRaises(ValueError):
            create(self.root, '0.27.1', 'a' * 40)

    def test_rejects_modified_missing_and_extra_files(self):
        for change in ['modified', 'missing', 'extra']:
            with self.subTest(change=change):
                self.setUp()
                create(self.root, '0.27.1', 'a' * 40)
                worker = self.root / 'workers/media-worker/worker.py'
                if change == 'modified': worker.write_bytes(b'other build')
                elif change == 'missing': worker.unlink()
                else: (self.root / 'unexpected').write_bytes(b'extra')
                with self.assertRaises(ValueError): verify(self.root)

    def test_missing_component_cannot_be_published(self):
        (self.root / 'workers/media-worker/worker.py').unlink()
        with self.assertRaises(ValueError): create(self.root, '0.27.1', 'a' * 40)

    def test_symlink_cannot_escape_inventory(self):
        (self.root / 'outside').symlink_to('/tmp')
        with self.assertRaises(ValueError): create(self.root, '0.27.1', 'a' * 40)

    def test_cli_requires_clean_matching_tag_and_verifies_without_git(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            (repo / 'scripts').mkdir()
            script = repo / 'scripts/release_manifest.py'
            shutil.copy2(Path(__file__).with_name('release_manifest.py'), script)
            (repo / '.gitignore').write_text('/dist/\n')
            def git(*args):
                subprocess.run(['git', '-C', str(repo), *args], check=True, capture_output=True)
            git('init')
            git('add', '.')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-m', 'fixture')
            git('tag', 'v0.27.1')
            bundle = repo / 'dist/bundle'
            shutil.copytree(self.root, bundle)
            def create_cli(version):
                return subprocess.run([sys.executable, str(script), 'create', str(bundle), '--version', version], capture_output=True)
            self.assertNotEqual(0, create_cli('0.27.2').returncode)
            (repo / 'untracked').write_text('not committed')
            self.assertNotEqual(0, create_cli('0.27.1').returncode)
            (repo / 'untracked').unlink()
            self.assertEqual(0, create_cli('0.27.1').returncode)
            shutil.rmtree(repo / '.git')
            self.assertEqual(0, subprocess.run([sys.executable, str(script), 'verify', str(bundle)], capture_output=True).returncode)
