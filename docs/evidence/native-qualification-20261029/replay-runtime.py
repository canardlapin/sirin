#!/usr/bin/env python3
"""Recreate a frozen private runtime; never patches the input or dependency cache."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

base = Path(__file__).resolve().parent/'runtime'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('variant', choices=['phase-diagnostic','phase-diagnostic-v2','saved-mask-control'])
parser.add_argument('source',type=Path)
parser.add_argument('output',type=Path)
a = parser.parse_args()
meta = json.loads((base/(a.variant+'.json')).read_text())
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
if sha(a.source) != meta['source_sha256']: parser.error('input JAR hash mismatch')
if a.output.exists() or a.output.resolve() == a.source.resolve(): parser.error('private new output required')
resources = meta['changed_resources']
for name in resources:
    if not name.startswith('scala-native/gc/') or '..' in Path(name).parts:
        parser.error('unexpected resource path')
contents = {n:(base/'resources'/a.variant/n).read_bytes() for n in resources}
a.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(a.source) as source,zipfile.ZipFile(a.output,'x',zipfile.ZIP_DEFLATED) as target:
    for info in source.infolist():
        target.writestr(info,contents[info.filename] if info.filename in contents else source.read(info))
actual = sha(a.output)
if actual != meta['output_sha256']: raise SystemExit('replay differs; output retained')
print(json.dumps({'variant':a.variant,'sha256':actual,'exact_replay':True}))
