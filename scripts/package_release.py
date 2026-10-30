#!/usr/bin/env python3
"""Package either component, or compose a suite from the exact component trees."""
import argparse
import hashlib
from pathlib import Path
import shutil
import tarfile
import zipfile
from release_manifest import create, source_identity, verify


def assemble(repo, output, version, commit, component):
    product = {'fileserver': 'MuYunFileServer', 'media-worker': 'MuYunMediaWorker', 'suite': 'MuYunFileSuite'}[component]
    root = output / f'{product}-{version}'
    if root.exists():
        raise ValueError('Immutable output already exists')
    root.mkdir(parents=True)
    (root / 'scripts').mkdir()
    shutil.copy2(repo / 'scripts/release_manifest.py', root / 'scripts/release_manifest.py')
    shutil.copytree(repo / 'contracts', root / 'contracts')
    if component == 'fileserver':
        shutil.copytree(repo / 'build/quarkus-app', root / 'quarkus-app')
        shutil.copytree(repo / 'docs', root / 'docs')
        for name in ('README.md', 'compose.yaml', 'compose.seaweedfs.yaml'):
            shutil.copy2(repo / name, root / name)
        for name in ('application.local.example.yml', 'application.minio.example.yml', 'application.seaweedfs.example.yml', 'RUN.md'):
            shutil.copy2(repo / 'distribution/release' / name, root / name)
        shutil.copy2(repo / 'src/main/resources/application.yml', root / 'application.yml')
        shutil.copy2(repo / 'scripts/render-seaweed-identities.py', root / 'scripts/render-seaweed-identities.py')
    elif component == 'media-worker':
        shutil.copytree(repo / 'docs', root / 'docs')
        for name in ('worker.py', 'README.md'):
            shutil.copy2(repo / 'workers/media-worker' / name, root / name)
        readme = root / 'README.md'
        readme.write_text(readme.read_text(encoding='utf-8').replace('../../docs/', 'docs/'), encoding='utf-8')
    else:
        for name, child_product in [('fileserver', 'MuYunFileServer'), ('media-worker', 'MuYunMediaWorker')]:
            child = output / f'{child_product}-{version}'
            verify(child)
            shutil.copytree(child, root / 'components' / name)
        shutil.copy2(repo / 'distribution/release/SUITE.md', root / 'README.md')
    create(root, version, commit, component)
    return root


def archive(root):
    tar = root.parent / (root.name + '.tar.gz')
    with tarfile.open(tar, 'w:gz') as bundle:
        bundle.add(root, arcname=root.name)
    zip_path = root.parent / (root.name + '.zip')
    with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as bundle:
        for path in sorted(root.rglob('*')):
            if path.is_file(): bundle.write(path, root.name + '/' + path.relative_to(root).as_posix())
    checksum = root.parent / (root.name + '.sha256')
    checksum.write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in (tar, zip_path)), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--component', choices=['fileserver', 'media-worker', 'all'], default='all')
    parser.add_argument('--output', type=Path, default=Path('dist'))
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    commit = source_identity(repo, args.version)
    for component in (['fileserver', 'media-worker', 'suite'] if args.component == 'all' else [args.component]):
        archive(assemble(repo, args.output.resolve(), args.version, commit, component))


if __name__ == '__main__': main()
