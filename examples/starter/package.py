#!/usr/bin/env python3
"""Package ReviewPad from its compiled JAR and an exported Sirin development SDK."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess


def verify(sdk):
    manifest = json.loads((sdk / 'sdk.json').read_text())
    if manifest['format'] != 1 or manifest['platform'] != 'macos-arm64':
        raise ValueError('This starter requires the macOS arm64 development SDK')
    if manifest['scala_version'] != '3.7.4' or manifest['jdk_major'] != 25:
        raise ValueError('SDK Scala/JDK versions do not match this starter')
    for name, expected in manifest['files'].items():
        path = sdk / name
        if not path.resolve().is_relative_to(sdk):
            raise ValueError('SDK entry escapes its root: ' + name)
        if 'link' in expected:
            if not path.is_symlink() or str(path.readlink()) != expected['link']:
                raise ValueError('Changed SDK link: ' + name)
        elif path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != expected['sha256']:
            raise ValueError('Changed SDK file: ' + name)
    actual = {p.relative_to(sdk).as_posix() for p in sdk.rglob('*') if p.is_file() or p.is_symlink()}
    if actual != set(manifest['files']) | {'sdk.json'}:
        raise ValueError('Unexpected or missing SDK files')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--file', type=Path, help='Optional explicit note path for an isolated acceptance image')
    args = parser.parse_args()
    sdk = args.sdk.resolve()
    manifest = verify(sdk)
    release = (args.java_home / 'release').read_text()
    version = re.search(r'^JAVA_VERSION="(\d+)', release, re.MULTILINE)
    if not version or int(version.group(1)) != manifest['jdk_major']:
        raise ValueError('Package with the JDK 25 runtime required by this SDK')
    note_path = str(args.file.absolute()) if args.file else None
    if note_path and any(char in note_path for char in '\n\r\0'):
        raise ValueError('A launcher note path cannot contain a line break or NUL')
    root = Path(__file__).resolve().parent
    target = root / 'target'
    inputs = target / 'package-input'
    output = target / 'package'
    # Only this project's reproducible packaging outputs are replaced.
    for path in [inputs, output]:
        if path.is_symlink():
            raise ValueError('Refusing a linked build output: ' + str(path))
        if path.exists():
            shutil.rmtree(path)
    shutil.copytree(sdk, inputs, symlinks=True, ignore=shutil.ignore_patterns('sdk.json'))
    shutil.copy2(args.jar, inputs / 'reviewpad.jar')
    command = [str(args.java_home / 'bin/jpackage'), '--type', 'app-image', '--name', 'SirinReviewPad',
               '--dest', str(output), '--input', str(inputs), '--main-jar', 'reviewpad.jar',
               '--main-class', 'reviewpad.ReviewPad', '--runtime-image', str(args.java_home),
               '--mac-package-identifier', 'io.github.canardlapin.sirin.starter.reviewpad',
               '--java-options', '-XstartOnFirstThread', '--java-options', '--enable-native-access=ALL-UNNAMED',
               '--java-options', '-Dsirin.desktop.library=$APPDIR/libsirin_desktop.dylib',
               '--java-options', '-Dsirin.repo=$APPDIR']
    subprocess.run(command, check=True)
    app = output / 'SirinReviewPad.app'
    if note_path:
        # jpackage tokenizes --arguments on whitespace. The launcher's CFG stores
        # each argument as one property, preserving spaces and quote characters.
        config = app / 'Contents/app/SirinReviewPad.cfg'
        text = config.read_text()
        if '[ArgOptions]' in text:
            raise ValueError('Unexpected launcher arguments in the generated image')
        config.write_text(text + '\n[ArgOptions]\narguments=--file\narguments=' + note_path + '\n')
        subprocess.run(['codesign', '--force', '--deep', '--sign', '-', str(app)], check=True)
    subprocess.run(['codesign', '--verify', '--deep', '--strict', str(app)], check=True)
    receipt = {'sdk_commit': manifest['source_commit'], 'sdk_dirty': manifest['source_dirty'],
               'sdk_manifest_sha256': hashlib.sha256((sdk / 'sdk.json').read_bytes()).hexdigest(),
               'application_jar_sha256': hashlib.sha256(args.jar.read_bytes()).hexdigest(),
               'native_library_sha256': hashlib.sha256((sdk / 'libsirin_desktop.dylib').read_bytes()).hexdigest(),
               'configured_note': note_path}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('ReviewPad image: ' + str(app))


if __name__ == '__main__':
    main()
