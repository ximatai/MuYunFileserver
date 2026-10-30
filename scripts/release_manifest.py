#!/usr/bin/env python3
"""Validate independently deployable components and tested component combinations."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

MANIFEST = 'release-manifest.json'
PRODUCTS = {'fileserver': 'MuYunFileServer', 'media-worker': 'MuYunMediaWorker', 'suite': 'MuYunFileSuite'}
REQUIRED = {'fileserver': ('quarkus-app/quarkus-run.jar', 'application.yml'),
            'media-worker': ('worker.py',),
            'suite': ('components/fileserver/release-manifest.json', 'components/media-worker/release-manifest.json')}


def inventory(root):
    result = {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError(f'Symlinks are not allowed in release bundles: {path}')
        if path.is_file() and path != root / MANIFEST:
            result[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def validate(data, root):
    component = data.get('component')
    if data.get('schemaVersion') != 1 or component not in PRODUCTS or data.get('product') != PRODUCTS[component]:
        raise ValueError('Unsupported component manifest')
    if not re.fullmatch(r'\d+\.\d+\.\d+', data.get('version', '')) or not re.fullmatch(r'[0-9a-f]{40}', data.get('sourceCommit', '')):
        raise ValueError('Invalid release identity')
    actual = inventory(root)
    if any(name not in actual for name in REQUIRED[component]):
        raise ValueError('Missing release component')
    if data.get('files') != actual:
        raise ValueError('Release files changed, missing or added')
    contract = json.loads((root / 'contracts/media-processing-v1.json').read_text(encoding='utf-8'))
    if contract.get('contract') != 'muyun.media-processing' or contract.get('version') != 1 or data.get('protocols') != {'mediaProcessing': 1}:
        raise ValueError('Unsupported media processing contract')
    if component == 'suite':
        expected = {}
        for name in ('fileserver', 'media-worker'):
            child = verify(root / 'components' / name)
            if child['component'] != name or child['protocols'] != data['protocols']:
                raise ValueError('Incompatible component combination')
            expected[name] = {'version': child['version'], 'sourceCommit': child['sourceCommit'],
                              'manifestSha256': actual[f'components/{name}/{MANIFEST}']}
        if data.get('components') != expected:
            raise ValueError('Component combination does not match manifests')
    return data


def create(root, version, commit, component='fileserver'):
    if (root / MANIFEST).exists():
        raise ValueError('Release manifest already exists')
    if component not in PRODUCTS:
        raise ValueError('Unknown component')
    data = {'schemaVersion': 1, 'component': component, 'product': PRODUCTS[component],
            'version': version, 'sourceCommit': commit, 'protocols': {'mediaProcessing': 1}, 'files': inventory(root)}
    if component == 'suite':
        data['components'] = {name: {'version': (child := verify(root / 'components' / name))['version'],
                             'sourceCommit': child['sourceCommit'],
                             'manifestSha256': data['files'][f'components/{name}/{MANIFEST}']}
                              for name in ('fileserver', 'media-worker')}
    validate(data, root)
    (root / MANIFEST).write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    return data


def verify(root):
    return validate(json.loads((root / MANIFEST).read_text(encoding='utf-8')), root)


def source_identity(repo, version):
    def git(*args):
        return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()
    if git('status', '--porcelain', '--untracked-files=normal'):
        raise ValueError('Official release requires a clean source checkout')
    commit = git('rev-parse', 'HEAD')
    if git('rev-parse', f'refs/tags/v{version}^{{commit}}') != commit:
        raise ValueError('Release tag must identify the checked-out source commit')
    return commit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['create', 'verify'])
    parser.add_argument('root', type=Path)
    parser.add_argument('--version')
    parser.add_argument('--component', choices=PRODUCTS, default='fileserver')
    args = parser.parse_args()
    if args.command == 'create':
        create(args.root, args.version or '', source_identity(Path(__file__).resolve().parents[1], args.version), args.component)
    data = verify(args.root)
    print(f"Verified {data['product']} {data['version']} ({data['sourceCommit']})")


if __name__ == '__main__':
    main()
