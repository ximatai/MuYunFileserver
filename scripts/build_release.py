"""Controlled FileServer build; bind the resulting bytes to a clean tagged checkout."""
import json
import os
from pathlib import Path
import subprocess
from release_manifest import inventory, source_identity


def build_fileserver(repo, version, commit):
    receipt = repo / 'build/release-build.json'
    receipt.unlink(missing_ok=True)
    if os.environ.get('VIEWER_PREBUILT_RESOURCES_DIR'):
        raise ValueError('Official releases must build the viewer from tagged source')
    if source_identity(repo, version) != commit:
        raise ValueError('Source changed before build')
    subprocess.run([str(repo / ('gradlew.bat' if os.name == 'nt' else 'gradlew')),
                    'clean', 'quarkusBuild', f'-PappVersion={version}', '--no-daemon'], cwd=repo, check=True)
    if source_identity(repo, version) != commit:
        raise ValueError('Source changed during build')
    receipt.write_text(json.dumps({'schemaVersion': 1, 'version': version, 'sourceCommit': commit,
                                   'files': inventory(repo / 'build/quarkus-app')}, indent=2) + '\n', encoding='utf-8')


def validate_build(repo, version, commit):
    path = repo / 'build/release-build.json'
    if not path.is_file():
        raise ValueError('Missing controlled build receipt; build through package_release.py')
    receipt = json.loads(path.read_text(encoding='utf-8'))
    if receipt.get('schemaVersion') != 1 or receipt.get('version') != version or receipt.get('sourceCommit') != commit:
        raise ValueError('Stale build identity')
    if receipt.get('files') != inventory(repo / 'build/quarkus-app'):
        raise ValueError('Built FileServer files changed after controlled build')
    return path
