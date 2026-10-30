import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from release_manifest import create, verify, MANIFEST
from package_release import assemble, archive

REPO = Path(__file__).resolve().parents[1]


class ReleaseManifestTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def component(self, name='fileserver', version='0.27.1'):
        root = self.root / name
        files = ['application.yml', 'quarkus-app/quarkus-run.jar'] if name == 'fileserver' else ['worker.py']
        for name in files:
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'fixture')
        shutil.copytree(REPO / 'contracts', root / 'contracts')
        return root

    def test_components_are_independent_and_versions_need_not_match(self):
        fs = self.component()
        worker = self.component('media-worker')
        create(fs, '0.27.1', 'a' * 40)
        create(worker, '0.27.2', 'b' * 40, 'media-worker')
        suite = self.root / 'suite'
        shutil.copytree(REPO / 'contracts', suite / 'contracts')
        for name, source in [('fileserver', fs), ('media-worker', worker)]:
            shutil.copytree(source, suite / 'components' / name)
        result = create(suite, '0.27.3', 'c' * 40, 'suite')
        self.assertEqual(result, verify(suite))
        self.assertEqual('0.27.2', result['components']['media-worker']['version'])
        with self.assertRaises(ValueError): create(fs, '0.27.1', 'a' * 40)

    def test_modified_missing_extra_and_symlink_rejected(self):
        for kind in ['modified', 'missing', 'extra', 'symlink']:
            with self.subTest(kind=kind):
                root = self.component('media-worker')
                create(root, '0.27.1', 'a' * 40, 'media-worker')
                if kind == 'modified': (root / 'worker.py').write_bytes(b'changed')
                elif kind == 'missing': (root / 'worker.py').unlink()
                elif kind == 'extra': (root / 'unknown').write_bytes(b'extra')
                else: (root / 'outside').symlink_to('/tmp')
                with self.assertRaises(ValueError): verify(root)
                shutil.rmtree(root)

    def test_incompatible_protocol_or_wrong_combination_rejected(self):
        root = self.component('media-worker')
        create(root, '0.27.1', 'a' * 40, 'media-worker')
        data = json.loads((root / MANIFEST).read_text())
        data['protocols']['mediaProcessing'] = 2
        (root / MANIFEST).write_text(json.dumps(data))
        with self.assertRaises(ValueError): verify(root)

    def test_missing_runtime_rejected(self):
        root = self.component('media-worker')
        (root / 'worker.py').unlink()
        with self.assertRaises(ValueError): create(root, '0.27.1', 'a' * 40, 'media-worker')

    def test_cli_requires_clean_matching_tag_and_verifies_without_git(self):
        repo = self.root / 'repo'
        (repo / 'scripts').mkdir(parents=True)
        script = repo / 'scripts/release_manifest.py'
        shutil.copy2(REPO / 'scripts/release_manifest.py', script)
        (repo / '.gitignore').write_text('/dist/\n')
        def git(*args): subprocess.run(['git', '-C', str(repo), *args], check=True, capture_output=True)
        git('init'); git('add', '.')
        git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-m', 'fixture')
        git('tag', 'v0.27.1')
        bundle = repo / 'dist/worker'
        shutil.copytree(self.component('media-worker'), bundle)
        def cli(version):
            return subprocess.run([sys.executable, str(script), 'create', str(bundle), '--component', 'media-worker', '--version', version], capture_output=True)
        self.assertNotEqual(0, cli('0.27.2').returncode)
        (repo / 'untracked').touch()
        self.assertNotEqual(0, cli('0.27.1').returncode)
        (repo / 'untracked').unlink()
        self.assertEqual(0, cli('0.27.1').returncode)
        shutil.rmtree(repo / '.git')
        self.assertEqual(0, subprocess.run([sys.executable, str(script), 'verify', str(bundle)], capture_output=True).returncode)

    def test_worker_packages_without_java_and_suite_reuses_component_bytes(self):
        repo = self.root / 'source'
        shutil.copytree(REPO, repo, ignore=shutil.ignore_patterns('.git', '.gradle', 'build', 'node_modules', 'var', 'dist', '.codex', '__pycache__', '.quarkus'))
        output = self.root / 'out'
        worker = assemble(repo, output, '0.27.1', 'a' * 40, 'media-worker')
        self.assertFalse((worker / 'quarkus-app').exists())
        self.assertEqual((REPO / 'workers/media-worker/worker.py').read_bytes(), (worker / 'worker.py').read_bytes())
        (repo / 'build/quarkus-app').mkdir(parents=True)
        (repo / 'build/quarkus-app/quarkus-run.jar').write_bytes(b'test jar')
        fs = assemble(repo, output, '0.27.1', 'a' * 40, 'fileserver')
        self.assertFalse((fs / 'worker.py').exists())
        suite = assemble(repo, output, '0.27.1', 'a' * 40, 'suite')
        for name, component in [('fileserver', fs), ('media-worker', worker)]:
            for file in component.rglob('*'):
                if file.is_file(): self.assertEqual(file.read_bytes(), (suite / 'components' / name / file.relative_to(component)).read_bytes())
        archive(suite)
        import tarfile
        unpacked = self.root / 'unpacked'
        # This archive is generated in this test from trusted fixture files.
        with tarfile.open(output / (suite.name + '.tar.gz')) as bundle: bundle.extractall(unpacked)
        verify(unpacked / suite.name)
        data = json.loads((suite / MANIFEST).read_text())
        data['components']['media-worker']['version'] = '0.99.0'
        (suite / MANIFEST).write_text(json.dumps(data))
        with self.assertRaises(ValueError): verify(suite)
