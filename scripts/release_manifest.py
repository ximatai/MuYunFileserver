#!/usr/bin/env python3
"""Create or verify the file inventory of one FileServer + Worker release."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

MANIFEST = 'release-manifest.json'


def inventory(root):
    result = {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError(f'Symlinks are not allowed in release bundles: {path}')
        if path.is_file() and path != root / MANIFEST:
            result[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def create(root, version, commit):
    if not re.fullmatch(r'\d+\.\d+\.\d+', version) or not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Expected a stable version and full source commit')
    files = inventory(root)
    required = ('quarkus-app/quarkus-run.jar', 'workers/media-worker/worker.py', 'application.yml')
    if any(name not in files for name in required):
        raise ValueError('Incomplete release: FileServer, Worker and default configuration are required')
    if (root / MANIFEST).exists():
        raise ValueError('Release manifest already exists; do not overwrite an immutable release')
    data = {'schemaVersion': 1, 'product': 'MuYunFileServer', 'version': version,
            'sourceCommit': commit, 'components': {'fileserver': 'quarkus-app',
            'media-worker': 'workers/media-worker'}, 'files': files}
    (root / MANIFEST).write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    return data


def verify(root):
    data = json.loads((root / MANIFEST).read_text(encoding='utf-8'))
    if data.get('schemaVersion') != 1 or data.get('product') != 'MuYunFileServer':
        raise ValueError('Unsupported release manifest')
    if data.get('components') != {'fileserver': 'quarkus-app', 'media-worker': 'workers/media-worker'}:
        raise ValueError('Unexpected release component layout')
    if not re.fullmatch(r'\d+\.\d+\.\d+', data.get('version', '')) or not re.fullmatch(r'[0-9a-f]{40}', data.get('sourceCommit', '')):
        raise ValueError('Invalid release identity')
    actual = inventory(root)
    if any(p not in actual for p in ('quarkus-app/quarkus-run.jar', 'workers/media-worker/worker.py', 'application.yml')):
        raise ValueError('Missing release component')
    if data.get('files') != actual:
        raise ValueError('Release files changed, missing or added')
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['create', 'verify'])
    parser.add_argument('root', type=Path)
    parser.add_argument('--version')
    args = parser.parse_args()
    if args.command == 'create':
        repo = Path(__file__).resolve().parents[1]
        def git(*parts):
            return subprocess.check_output(['git', '-C', str(repo), *parts], text=True).strip()
        if git('status', '--porcelain', '--untracked-files=normal'):
            raise ValueError('Official release requires a clean source checkout')
        commit = git('rev-parse', 'HEAD')
        if git('rev-parse', 'refs/tags/v' + (args.version or '') + '^{commit}') != commit:
            raise ValueError('Release tag must identify the checked-out source commit')
        create(args.root, args.version or '', commit)
    data = verify(args.root)
    print(f"Verified MuYunFileServer {data['version']} ({data['sourceCommit']}), including media-worker")


if __name__ == '__main__':
    main()
