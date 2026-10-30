#!/usr/bin/env python3
"""Validate component identity, file integrity and provided/required protocol capabilities."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

MANIFEST = 'release-manifest.json'
PRODUCTS = {'fileserver': 'MuYunFileServer', 'media-worker': 'MuYunMediaWorker', 'suite': 'MuYunFileSuite'}
REQUIRED = {'fileserver': ('quarkus-app/quarkus-run.jar', 'application.yml', 'build-origin.json'),
            'media-worker': ('worker.py', 'RUN.md'),
            'suite': ('components/fileserver/release-manifest.json', 'components/media-worker/release-manifest.json')}


def inventory(root):
    result = {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError(f'Symlinks are not allowed in release bundles: {path}')
        if path.is_file() and path != root / MANIFEST:
            result[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def capabilities(root, component):
    contract = json.loads((root / 'contracts/components.json').read_text(encoding='utf-8'))
    if contract.get('schemaVersion') != 1 or set(contract.get('components', {})) != {'fileserver', 'media-worker'}:
        raise ValueError('Unsupported component capability declarations')
    for value in contract['components'].values():
        if not isinstance(value, dict) or set(value) != {'provides', 'requires'}:
            raise ValueError('Expected provides/requires declarations')
        for entries in value.values():
            if not isinstance(entries, dict): raise ValueError('Expected named protocol map')
            for name, versions in entries.items():
                if not re.fullmatch(r'[a-z][A-Za-z0-9]*', name) or not isinstance(versions, list) or not versions:
                    raise ValueError('Invalid protocol declaration')
                if any(type(v) is not int or v < 1 for v in versions) or versions != sorted(set(versions)):
                    raise ValueError('Protocol versions must be unique positive integers')
    return contract['components'][component] if component != 'suite' else {'provides': {}, 'requires': {}}


def match_requirements(children):
    for consumer, data in children.items():
        for name, required in data['capabilities']['requires'].items():
            offered = {version for provider, candidate in children.items() if provider != consumer
                       for version in candidate['capabilities']['provides'].get(name, [])}
            if not offered.intersection(required):
                raise ValueError(f'Unsatisfied protocol {name} required by {consumer}')


def validate(data, root):
    component = data.get('component')
    if data.get('schemaVersion') != 2 or component not in PRODUCTS or data.get('product') != PRODUCTS[component]:
        raise ValueError('Unsupported component manifest; schema v1 releases use their bundled verifier')
    if not re.fullmatch(r'\d+\.\d+\.\d+', data.get('version', '')) or not re.fullmatch(r'[0-9a-f]{40}', data.get('sourceCommit', '')):
        raise ValueError('Invalid release identity')
    actual = inventory(root)
    if any(name not in actual for name in (*REQUIRED[component], 'LICENSE')):
        raise ValueError('Missing release component or license')
    if data.get('files') != actual:
        raise ValueError('Release files changed, missing or added')
    if data.get('capabilities') != capabilities(root, component):
        raise ValueError('Manifest does not match protocol declarations')
    if component == 'fileserver':
        origin = json.loads((root / 'build-origin.json').read_text(encoding='utf-8'))
        if origin.get('schemaVersion') != 1 or any(origin.get(k) != data[k] for k in ('version', 'sourceCommit')):
            raise ValueError('FileServer build identity mismatch')
        if origin.get('files') != inventory(root / 'quarkus-app'):
            raise ValueError('FileServer build bytes do not match origin')
    if component == 'suite':
        children = {name: verify(root / 'components' / name) for name in ('fileserver', 'media-worker')}
        if any(child['component'] != name for name, child in children.items()):
            raise ValueError('Wrong component layout')
        match_requirements(children)
        expected = {name: {'version': child['version'], 'sourceCommit': child['sourceCommit'],
                          'manifestSha256': actual[f'components/{name}/{MANIFEST}'], 'capabilities': child['capabilities']}
                    for name, child in children.items()}
        if data.get('components') != expected:
            raise ValueError('Component combination does not match manifests')
    return data


def create(root, version, commit, component='fileserver'):
    if (root / MANIFEST).exists(): raise ValueError('Release manifest already exists')
    if component not in PRODUCTS: raise ValueError('Unknown component')
    data = {'schemaVersion': 2, 'component': component, 'product': PRODUCTS[component],
            'version': version, 'sourceCommit': commit, 'capabilities': capabilities(root, component), 'files': inventory(root)}
    if component == 'suite':
        data['components'] = {name: {'version': (child := verify(root / 'components' / name))['version'],
                             'sourceCommit': child['sourceCommit'], 'capabilities': child['capabilities'],
                             'manifestSha256': data['files'][f'components/{name}/{MANIFEST}']}
                              for name in ('fileserver', 'media-worker')}
    validate(data, root)
    (root / MANIFEST).write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    return data


def verify(root):
    return validate(json.loads((root / MANIFEST).read_text(encoding='utf-8')), root)


def source_identity(repo, version):
    def git(*args): return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()
    if git('status', '--porcelain', '--untracked-files=normal'): raise ValueError('Official release requires a clean source checkout')
    commit = git('rev-parse', 'HEAD')
    if git('rev-parse', f'refs/tags/v{version}^{{commit}}') != commit: raise ValueError('Release tag must identify the checked-out source commit')
    return commit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['create', 'verify'])
    parser.add_argument('root', type=Path)
    parser.add_argument('--version')
    parser.add_argument('--component', choices=PRODUCTS, default='fileserver')
    args = parser.parse_args()
    if args.command == 'create': create(args.root, args.version or '', source_identity(Path(__file__).resolve().parents[1], args.version), args.component)
    data = verify(args.root)
    print(f"Verified {data['product']} {data['version']} ({data['sourceCommit']})")


if __name__ == '__main__': main()
