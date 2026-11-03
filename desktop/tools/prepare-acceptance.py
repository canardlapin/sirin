#!/usr/bin/env python3
"""Configure fresh local acceptance copies without changing production app code."""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import re
import subprocess
import sys


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def configure(source, destination, bundle, arguments, inspector=False):
    subprocess.run(['/bin/cp', '-cRp', str(source), str(destination)], check=True)
    plist = destination / 'Contents/Info.plist'
    info = plistlib.loads(plist.read_bytes())
    original_bundle = info['CFBundleIdentifier']
    info['CFBundleIdentifier'] = bundle
    plist.write_bytes(plistlib.dumps(info))
    cfg = destination / 'Contents/app' / (source.stem + '.cfg')
    before = cfg.read_text()
    sections = before.split('[ArgOptions]')
    if len(sections) > 2:
        raise ValueError('Duplicate launcher argument section')
    text = sections[0].rstrip() + '\n'
    if inspector:
        if '[JavaOptions]' not in text:
            raise ValueError('Missing Java options section')
        text += 'java-options=-Dsirin.inspector=true\n'
    if any(any(char in value for char in '\n\r\0') for value in arguments):
        raise ValueError('An argument contains a line break or NUL')
    text += '\n[ArgOptions]\n' + ''.join('arguments=' + value + '\n' for value in arguments)
    cfg.write_text(text)
    if 'delay' in text.lower():
        raise ValueError('Acceptance cannot include a delay wrapper')
    for relative in ['sirin.jar', 'workbench.jar', 'reviewpad.jar', 'libsirin_desktop.dylib']:
        original = source / 'Contents/app' / relative
        if original.exists() and digest(original) != digest(destination / 'Contents/app' / relative):
            raise ValueError('Changed application code or native transport: ' + relative)
    subprocess.run(['codesign', '--force', '--deep', '--sign', '-', str(destination)], check=True)
    subprocess.run(['codesign', '--verify', '--deep', '--strict', str(destination)], check=True)
    for relative in ['sirin.jar', 'workbench.jar', 'reviewpad.jar', 'libsirin_desktop.dylib']:
        original = source / 'Contents/app' / relative
        if original.exists() and digest(original) != digest(destination / 'Contents/app' / relative):
            raise ValueError('Signing changed application code or native transport: ' + relative)
    return {'app': str(destination), 'source': str(source), 'original_bundle_id': original_bundle,
            'bundle_id': bundle, 'arguments': arguments, 'inspector': inspector,
            'original_cfg_sha256': hashlib.sha256(before.encode()).hexdigest(),
            'cfg_sha256': digest(cfg), 'production_code_unchanged': True,
            'codesign_deep_strict_valid': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['workbench', 'scalafx', 'starter', 'sdk', 'output', 'documents']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--bundle-prefix', default='io.github.canardlapin.sirin.acceptance20261102')
    args = parser.parse_args()
    if args.output.is_symlink():
        raise ValueError('Choose an empty, unlinked acceptance output directory')
    output = args.output.resolve()
    documents = args.documents.resolve()
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError('Choose an empty, unlinked acceptance output directory')
    if not re.fullmatch(r'[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+', args.bundle_prefix):
        raise ValueError('Choose a reverse-DNS bundle prefix unique to this review')
    receipts = output.parent / 'receipts'
    if (receipts / 'apps.json').exists() or (receipts / 'base-native-closure.json').exists():
        raise ValueError('Choose a fresh review root; existing receipts must be preserved')
    for key in ['workbench', 'scalafx', 'reviewpad', 'inspector']:
        if not (documents / key).is_dir():
            raise ValueError('Missing isolated document directory: ' + str(documents / key))
    for source in [args.workbench, args.scalafx, args.starter]:
        if source.suffix != '.app' or not (source / 'Contents/Info.plist').is_file():
            raise ValueError('Missing source app: ' + str(source))
    sdk = json.loads((args.sdk / 'sdk.json').read_text())
    if sdk['source_dirty']:
        raise ValueError('Build the SDK from a clean source checkout')
    workbench_jar = args.workbench / 'Contents/app/sirin.jar'
    starter_jar = args.starter / 'Contents/app/sirin.jar'
    workbench_hash = digest(workbench_jar)
    if workbench_hash != sdk['files']['sirin.jar']['sha256'] or digest(starter_jar) != workbench_hash:
        raise ValueError('Starter and workbench must use the exact exported SDK')
    output.mkdir(parents=True, exist_ok=True)
    receipts.mkdir(parents=True, exist_ok=True)
    portable = Path(__file__).with_name('portable-image.py')
    subprocess.run([sys.executable, str(portable), str(args.workbench), '--load-library',
                    '--out', str(receipts / 'base-native-closure.json')], check=True)
    entries = []
    for key, source, inspector in [('workbench', args.workbench, False),
                                   ('scalafx', args.scalafx, False),
                                   ('reviewpad', args.starter, False),
                                   ('inspector', args.workbench, True)]:
        parent = output / key
        parent.mkdir()
        destination = parent / source.name
        folder = documents / key
        arguments = ['--file', str(folder / 'review.note')] if key == 'reviewpad' else ['--documents', str(folder)]
        entries.append(configure(source.resolve(), destination,
                       args.bundle_prefix + '.' + key, arguments, inspector))
    files = {}
    for path in sorted(output.rglob('*')):
        name = path.relative_to(output).as_posix()
        if path.is_symlink():
            files[name] = {'link': str(path.readlink())}
        elif path.is_file():
            files[name] = {'bytes': path.stat().st_size, 'sha256': digest(path)}
    receipt = {'source_commit': sdk['source_commit'], 'sdk_manifest_sha256': digest(args.sdk / 'sdk.json'),
               'apps': entries, 'files': files, 'ui_launched': False,
               'limits': 'Local synthetic-data acceptance copies with absolute document paths; '
                         'packaging changes only. No delay wrapper, latency comparison or human acceptance.'}
    with (receipts / 'apps.json').open('x') as stream:
        json.dump(receipt, stream, indent=2)
        stream.write('\n')
    print(json.dumps({'source_commit': sdk['source_commit'], 'apps': entries}, indent=2))


if __name__ == '__main__':
    main()
