#!/usr/bin/env python3
"""Exercise the installed FileServer package with read-only code and external config/data."""
import argparse
import json
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
from release_manifest import verify


def check(root):
    verify(root)
    modes = {p: p.stat().st_mode & 0o777 for p in [root, *root.rglob('*')]}
    process = None
    try:
        for p in modes: p.chmod(0o555 if p.is_dir() else 0o444)
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            work = directory / 'work'
            for name in ('storage', 'tmp', 'data'): (work / 'var' / name).mkdir(parents=True, exist_ok=True)
            config = directory / 'application.yml'
            shutil.copy2(root / 'application.local.example.yml', config)
            with socket.socket() as reservation:
                reservation.bind(('127.0.0.1', 0))
                port = reservation.getsockname()[1]
            with (directory / 'server.log').open('w') as log:
                process = subprocess.Popen(['java', '-Xmx256m', '-Dquarkus.config.locations=' + str(config),
                    '-Dquarkus.http.host=127.0.0.1', '-Dquarkus.http.port=' + str(port),
                    '-Dmfs.viewer.pdf-rendering.office-enabled=false', '-jar', str(root / 'quarkus-app/quarkus-run.jar')],
                    cwd=work, stdout=log, stderr=log)
                for _ in range(120):
                    try:
                        with urllib.request.urlopen(f'http://127.0.0.1:{port}/q/health/ready', timeout=1) as response:
                            if json.load(response)['status'] == 'UP': break
                    except OSError: pass
                    if process.poll() is not None: raise RuntimeError((directory / 'server.log').read_text()[-4000:])
                    time.sleep(.5)
                else: raise RuntimeError('External-directory startup timed out')
                if not (work / 'var/data/muyun-fileserver.db').is_file(): raise RuntimeError('Database not created in external work directory')
                process.terminate(); process.wait(timeout=20); process = None
            verify(root)
    finally:
        if process is not None:
            process.terminate()
            try: process.wait(timeout=20)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)
        for path, mode in modes.items(): path.chmod(mode)
    print('Read-only release, external config/data and post-startup integrity verified.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    check(parser.parse_args().root.resolve())
