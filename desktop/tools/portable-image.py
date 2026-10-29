#!/usr/bin/env python3
"""Inspect a relocated development app's native closure without launching UI."""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import struct
import subprocess


def commands(path):
    data = path.read_bytes()
    base = 0
    if struct.unpack_from('>I', data)[0] == 0xcafebabe:
        count = struct.unpack_from('>I', data, 4)[0]
        arches = [struct.unpack_from('>iiIII', data, 8+20*i) for i in range(count)]
        base = next(a[2] for a in arches if a[0] == 0x0100000c)
    if struct.unpack_from('<I', data, base)[0] != 0xfeedfacf:
        raise ValueError('expected arm64 Mach-O: '+str(path))
    count = struct.unpack_from('<I', data, base+16)[0]
    offset = base+32
    result = []
    for _ in range(count):
        kind, size = struct.unpack_from('<II', data, offset)
        if kind in (0xd,0xc,0x8000001c,0x80000018,0x8000001f,0x80000023):
            start = struct.unpack_from('<I', data, offset+8)[0]
            value = data[offset+start:offset+size].split(b'\0')[0].decode()
            result.append({'kind':hex(kind),'path':value})
        offset += size
    return result


def inspect(app, load_library=False):
    app = app.resolve()
    folder = app/'Contents/app'
    library = folder/'libsirin_desktop.dylib'
    sdl = folder/'SDL3.framework/Versions/A/SDL3'
    cfg = folder/(app.stem+'.cfg')
    links = commands(library)
    assert [r['path'] for r in links if r['kind']=='0xd'] == ['@rpath/libsirin_desktop.dylib']
    assert [r['path'] for r in links if r['kind']=='0x8000001c'] == ['@loader_path']
    assert sdl.is_file()
    framework = folder/'SDL3.framework'
    for name in ['SDL3','Resources','Headers','Versions/Current']:
        assert (framework/name).is_symlink(), 'flattened framework link: '+name
    assert (framework/'Versions/Current').resolve() == (framework/'Versions/A').resolve()
    for path in [library,sdl]:
        for item in commands(path):
            value = item['path']
            assert not value.startswith(('/private/tmp/','/Users/','/tmp/')), value
            if item['kind'] in ['0xc','0x80000018','0x8000001f','0x80000023']:
                if value.startswith('@rpath/'):
                    assert (folder/value.removeprefix('@rpath/')).is_file(), value
                else:
                    assert value.startswith(('/System/Library/','/usr/lib/')), value
    text = cfg.read_text()
    assert 'java-options=-Dsirin.repo=$APPDIR' in text
    assert 'java-options=-Dsirin.desktop.library=$APPDIR/libsirin_desktop.dylib' in text
    assert '/private/tmp/' not in text and '/Users/' not in text
    fonts = folder/'graphics/src/test/resources/fonts'
    for name in ['NotoSans-Regular.ttf','NotoSansHebrew-Regular.ttf','NotoNaskhArabic-Regular.ttf','NotoSansDevanagari-Regular.ttf','OFL.txt']:
        assert (fonts/name).is_file(), name
    for line in (fonts/'SHA256SUMS').read_text().splitlines():
        expected, name = line.split(maxsplit=1)
        assert hashlib.sha256((fonts/name).read_bytes()).hexdigest() == expected, name
    signature = subprocess.run(['/usr/bin/codesign','--verify','--deep','--strict',str(app)],
                               capture_output=True,text=True)
    assert signature.returncode == 0, signature.stderr
    # The packaged framework must not retain symlinks out to the source vendor tree.
    for path in folder.rglob('*'):
        if path.is_symlink():
            assert path.resolve().is_relative_to(folder), str(path)
    if load_library:
        # dlopen only: no window/input/render functions or app launcher invoked.
        ctypes.CDLL(str(library))
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    return {'app':str(app),'library_sha256':digest(library),'sdl_sha256':digest(sdl),
            'cfg_sha256':digest(cfg),'load_commands':links,'native_closure_valid':True,'codesign_deep_strict_valid':True,
            'library_loaded_without_UI':load_library,'packaged_font_files':len(list(fonts.iterdir())),
            'scope':'macOS arm64 development image, system frameworks/fonts and OS/JDK requirements remain; no UI/IME/shipping qualification'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('app',type=Path)
    parser.add_argument('--load-library',action='store_true')
    parser.add_argument('--out',type=Path)
    args = parser.parse_args()
    result = inspect(args.app,args.load_library)
    text = json.dumps(result,indent=2)+'\n'
    if args.out:
        with args.out.open('x') as output: output.write(text)
    print(text)
