#!/usr/bin/env python3
"""Verify the frozen budget, measured source hashes and lossless pixel artifacts."""
import hashlib
import json
from pathlib import Path
import struct
import zlib

here = Path(__file__).resolve().parent
root = here.parents[3]
contract_path = here / "contract.json"
assert hashlib.sha256(contract_path.read_bytes()).hexdigest() == "9036581d266e355cb7abb30f0a2cadd582fde2bd62e10a386d162c64e0e46093"
contract = json.loads(contract_path.read_text())
inputs = json.loads((here / "inputs.json").read_text())
for relative, expected in inputs["sha256"].items():
    assert hashlib.sha256((root / relative).read_bytes()).hexdigest() == expected, relative
measure = json.loads((here / "corpus/measurements.json").read_text())
assert [r["scale"] for r in measure["rows"]] == contract["scales"]
for row in measure["rows"]:
    scale = row["scale"]
    pixels = int(320 * scale) * int(180 * scale)
    assert row["oracleChecked"] + row["oracleExcluded"] == pixels
    assert row["oracleChecked"] > pixels / 2
    assert row["oracleWorstByteError"] <= contract["quality"]["pixel_tolerance_bytes"]
    assert row["coldMs"]["p95"] <= contract["measurement"]["cpu_prepare_p95_ms"]
    assert row["submitCompleteMs"]["p95"] <= contract["measurement"]["warm_submit_complete_p95_ms"]
    assert row["warmUploadBytes"] == row["warmRasterizations"] == 0
    assert row["overlayMeshUploadBytes"] == 48
    assert row["triangles"] <= contract["resources"]["max_triangles_per_compile"]
    assert row["statsBeforeUpdates"]["payloadBytes"] <= contract["resources"]["gpu_payload_bytes"]
    assert row["timingBudgetPassed"]
count = 0
for path in here.rglob("*.png"):
    blob = path.read_bytes()
    assert blob[:8] == b"\x89PNG\r\n\x1a\n", path
    at = 8
    compressed = b""
    width = height = None
    while at < len(blob):
        size = struct.unpack(">I", blob[at:at+4])[0]
        name = blob[at+4:at+8]
        data = blob[at+8:at+8+size]
        crc = struct.unpack(">I", blob[at+8+size:at+12+size])[0]
        assert zlib.crc32(name+data) & 0xffffffff == crc, path
        if name == b"IHDR":
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", data)
            assert (depth, color, compression, filtering, interlace) == (8, 6, 0, 0, 0)
        if name == b"IDAT":
            compressed += data
        at += size + 12
    rows = zlib.decompress(compressed)
    stride = width * 4 + 1
    assert len(rows) == stride * height
    assert all(rows[y*stride] == 0 for y in range(height)), path
    rgba = b"".join(rows[y*stride+1:(y+1)*stride] for y in range(height))
    assert rgba == path.with_suffix(".rgba").read_bytes(), path
    count += 1
manifest_path = here / "manifest.json"
if manifest_path.exists():
    manifest = json.loads(manifest_path.read_text())
    for relative, expected in manifest["sha256"].items():
        assert hashlib.sha256((root / relative).read_bytes()).hexdigest() == expected, relative
print(f"Verified frozen budgets, {len(inputs['sha256'])} input hashes, four-scale measurements and {count} lossless PNG/readback pairs.")
