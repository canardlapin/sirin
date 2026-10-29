#!/usr/bin/env python3
"""Recreate one private runtime JAR from frozen embedded resources and input hash.

Usage: replay-runtime.py VARIANT SOURCE_JAR OUTPUT_JAR
VARIANT: trap-diagnostic, trap-mask-diagnostic, trap-nodefer-control.
The mask diagnostic consumes the first diagnostic JAR; nodefer consumes original.
"""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

BASE = Path(__file__).resolve().parent / "runtime"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("variant", choices=["trap-diagnostic", "trap-mask-diagnostic", "trap-nodefer-control"])
parser.add_argument("source", type=Path)
parser.add_argument("output", type=Path)
a = parser.parse_args()
metadata = json.loads((BASE / (a.variant + ".json")).read_text())
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
if sha(a.source) != metadata["source_sha256"]:
    parser.error("source JAR does not match the frozen input")
if a.output.exists() or a.source.resolve() == a.output.resolve():
    parser.error("output must be a new private file")
names = metadata.get("changed_resources", [metadata.get("changed_resource")])
for name in names:
    if not name.startswith("scala-native/gc/immix/") or ".." in Path(name).parts:
        parser.error("unexpected runtime resource")
replacements = {name: (BASE / "resources" / a.variant / name).read_bytes() for name in names}
a.output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(a.source) as source, zipfile.ZipFile(a.output, "x", zipfile.ZIP_DEFLATED) as target:
    if not set(names).issubset(source.namelist()):
        raise ValueError("input is missing a replacement resource")
    for info in source.infolist():
        target.writestr(info, replacements.get(info.filename, source.read(info)))
actual = sha(a.output)
if actual != metadata["output_sha256"]:
    raise SystemExit("Replayed bytes differ; preserved output: " + str(a.output))
print(json.dumps({"variant": a.variant, "sha256": actual, "exact_replay": True}))
