import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from release_manifest import create, verify, MANIFEST, inventory
from build_release import build_fileserver, validate_build
from unittest.mock import patch
from package_release import assemble, archive

REPO = Path(__file__).resolve().parents[1]


class ReleaseManifestTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def component(self, name='fileserver', version='0.27.1'):
        root = self.root / name
        files = ['application.yml', 'quarkus-app/quarkus-run.jar'] if name == 'fileserver' else ['worker.py', 'RUN.md']
        for file_name in files:
            path = root / file_name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'fixture')
        shutil.copytree(REPO / 'contracts', root / 'contracts')
        shutil.copy2(REPO / 'LICENSE', root / 'LICENSE')
        if name == 'fileserver':
            (root / 'build-origin.json').write_text(json.dumps({'schemaVersion':1,'version':version,'sourceCommit':'a'*40,'files':inventory(root/'quarkus-app')}))
        return root

    def test_components_are_independent_and_versions_need_not_match(self):
        fs = self.component()
        worker = self.component('media-worker')
        create(fs, '0.27.1', 'a' * 40)
        create(worker, '0.27.2', 'b' * 40, 'media-worker')
        suite = self.root / 'suite'
        shutil.copytree(REPO / 'contracts', suite / 'contracts')
        shutil.copy2(REPO/'LICENSE', suite/'LICENSE')
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
        data['capabilities']['requires']['fileReception'] = [2]
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
        repo.mkdir()
        for directory in ('scripts', 'contracts', 'docs', 'distribution', 'src/main/resources', 'workers/media-worker'):
            shutil.copytree(REPO / directory, repo / directory, ignore=shutil.ignore_patterns('__pycache__'))
        for name in ('README.md', 'LICENSE', 'compose.yaml', 'compose.seaweedfs.yaml'):
            shutil.copy2(REPO / name, repo / name)
        output = self.root / 'out'
        worker = assemble(repo, output, '0.27.1', 'a' * 40, 'media-worker')
        self.assertFalse((worker / 'quarkus-app').exists())
        self.assertEqual((REPO / 'workers/media-worker/worker.py').read_bytes(), (worker / 'worker.py').read_bytes())
        (repo / 'build/quarkus-app').mkdir(parents=True)
        (repo / 'build/quarkus-app/quarkus-run.jar').write_bytes(b'test jar')
        (repo / 'build/release-build.json').write_text(json.dumps({'schemaVersion':1,'version':'0.27.1','sourceCommit':'a'*40,'files':inventory(repo/'build/quarkus-app')}))
        fs = assemble(repo, output, '0.27.1', 'a' * 40, 'fileserver')
        self.assertFalse((fs / 'worker.py').exists())
        suite = assemble(repo, output, '0.27.1', 'a' * 40, 'suite')
        for name, component in [('fileserver', fs), ('media-worker', worker)]:
            for file in component.rglob('*'):
                if file.is_file(): self.assertEqual(file.read_bytes(), (suite / 'components' / name / file.relative_to(component)).read_bytes())
        for root in (fs, worker, suite):
            self.assertEqual((REPO/'LICENSE').read_bytes(), (root/'LICENSE').read_bytes())
        self.assertTrue((worker/'RUN.md').is_file())
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

    def test_worker_jobs_can_evolve_without_upgrading_file_reception(self):
        fs = self.component()
        worker = self.component('media-worker')
        declarations = worker / 'contracts/components.json'
        value = json.loads(declarations.read_text())
        value['components']['media-worker']['provides']['workerJobs'] = [2]
        declarations.write_text(json.dumps(value))
        create(fs, '0.27.1', 'a'*40)
        create(worker, '0.28.0', 'b'*40, 'media-worker')
        suite = self.root/'suite'
        shutil.copytree(REPO/'contracts', suite/'contracts')
        shutil.copy2(REPO/'LICENSE', suite/'LICENSE')
        for name, child in [('fileserver',fs),('media-worker',worker)]:
            shutil.copytree(child, suite/'components'/name)
        create(suite,'0.28.0','c'*40,'suite')
        self.assertEqual([2],verify(suite)['components']['media-worker']['capabilities']['provides']['workerJobs'])
        shutil.rmtree(suite)
        shutil.rmtree(worker)
        worker=self.component('media-worker')
        value=json.loads((worker/'contracts/components.json').read_text())
        value['components']['media-worker']['requires']['fileReception']=[2]
        (worker/'contracts/components.json').write_text(json.dumps(value))
        create(worker,'0.28.0','b'*40,'media-worker')
        shutil.copytree(REPO/'contracts',suite/'contracts')
        shutil.copy2(REPO/'LICENSE',suite/'LICENSE')
        for name,child in [('fileserver',fs),('media-worker',worker)]:shutil.copytree(child,suite/'components'/name)
        with self.assertRaisesRegex(ValueError,'Unsatisfied protocol'):create(suite,'0.28.0','c'*40,'suite')

    def test_controlled_build_rejects_old_changed_or_failed_builds(self):
        repo=self.root/'repo';(repo/'build/quarkus-app').mkdir(parents=True)
        binary=repo/'build/quarkus-app/quarkus-run.jar';binary.write_bytes(b'old')
        with self.assertRaisesRegex(ValueError,'Missing controlled'):validate_build(repo,'0.27.2','a'*40)
        def build(*args,**kwargs):
            self.assertIn('clean',args[0]);self.assertIn('quarkusBuild',args[0]);self.assertIn('-PappVersion=0.27.2',args[0])
            binary.write_bytes(b'new')
        with patch('build_release.source_identity',return_value='a'*40),patch('build_release.subprocess.run',side_effect=build),patch.dict('os.environ',{'VIEWER_PREBUILT_RESOURCES_DIR':''}):
            build_fileserver(repo,'0.27.2','a'*40)
        validate_build(repo,'0.27.2','a'*40)
        with self.assertRaisesRegex(ValueError,'Stale build'):validate_build(repo,'0.27.3','a'*40)
        with self.assertRaisesRegex(ValueError,'Stale build'):validate_build(repo,'0.27.2','b'*40)
        binary.write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError,'changed'):validate_build(repo,'0.27.2','a'*40)
        with patch('build_release.source_identity',side_effect=['a'*40,'b'*40]),patch('build_release.subprocess.run',side_effect=build),patch.dict('os.environ',{'VIEWER_PREBUILT_RESOURCES_DIR':''}):
            with self.assertRaisesRegex(ValueError,'Source changed during'):build_fileserver(repo,'0.27.2','a'*40)
        self.assertFalse((repo/'build/release-build.json').exists())
        with patch.dict('os.environ',{'VIEWER_PREBUILT_RESOURCES_DIR':'old-viewer'}):
            with self.assertRaisesRegex(ValueError,'viewer'):build_fileserver(repo,'0.27.2','a'*40)

    def test_file_origin_and_license_required_even_when_hashes_recomputed(self):
        root=self.component()
        origin=json.loads((root/'build-origin.json').read_text());origin['sourceCommit']='b'*40
        (root/'build-origin.json').write_text(json.dumps(origin))
        with self.assertRaisesRegex(ValueError,'identity mismatch'):create(root,'0.27.1','a'*40)
        root=self.component('media-worker');(root/'LICENSE').unlink()
        with self.assertRaisesRegex(ValueError,'license'):create(root,'0.27.1','a'*40,'media-worker')
