#!/usr/bin/env python3
"""Export a local development SDK from an already built Sirin app image."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


def inventory(root):
    result = {}
    for path in sorted(root.rglob('*')):
        name = path.relative_to(root).as_posix()
        if path.is_symlink():
            if not path.resolve().is_relative_to(root.resolve()):
                raise ValueError('SDK link escapes its root: ' + name)
            result[name] = {'link': str(path.readlink())}
        elif path.is_file():
            result[name] = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('app', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    app = args.app.resolve()
    output = args.output.absolute()
    if output.exists():
        raise ValueError('Choose a new SDK output directory: ' + str(output))
    subprocess.run([sys.executable, str(repo / 'desktop/tools/portable-image.py'), str(app),
                    '--load-library'], check=True)
    source = app / 'Contents/app'
    shutil.copytree(source, output, symlinks=True, ignore=shutil.ignore_patterns('*.cfg'))
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo, text=True).strip())
    manifest = {'format': 1, 'source_commit': commit, 'source_dirty': dirty,
                'scala_version': '3.7.4', 'jdk_major': 25, 'platform': 'macos-arm64',
                'scope': 'Local development SDK; no published API or redistribution qualification',
                'files': inventory(output)}
    (output / 'sdk.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('Development SDK: ' + str(output))
    print('Source commit: ' + commit + (' (dirty checkout)' if dirty else ' (clean checkout)'))


if __name__ == '__main__':
    main()
