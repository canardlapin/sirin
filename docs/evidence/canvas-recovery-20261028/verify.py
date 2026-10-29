#!/usr/bin/env python3
"""Replay evidence checks, preserving diagnostic and qualification boundaries."""
import csv
import hashlib
import json
import math
from pathlib import Path
import sys

evidence = Path(__file__).resolve().parent
repo = evidence.parents[2]


def read(name):
    return json.loads((evidence / name).read_text())


for relative, expected in read("sources.json")["files"].items():
    assert hashlib.sha256((repo / relative).read_bytes()).hexdigest() == expected, relative
for relative, expected in read("artifacts.json").items():
    assert hashlib.sha256((evidence / relative).read_bytes()).hexdigest() == expected, relative
pixels = read("pixels.json")
assert len(pixels) == 16 and {p["scale"] for p in pixels} == {1, 1.25, 1.5, 2}
for item in pixels:
    actual = (evidence / "pixels" / (item["stem"] + "-actual.rgba")).read_bytes()
    reference = (evidence / "pixels" / (item["stem"] + "-reference.rgba")).read_bytes()
    assert len(actual) == len(reference) == item["width"] * item["height"] * 4
    assert item["oracle_checked"] + item["oracle_excluded"] == item["width"] * item["height"]
    worst = max(abs(a - b) for a, b in zip(actual, reference))
    assert worst == item["all_pixel_max_byte_error"] == 0
    assert item["oracle_mismatches"] == 0

rows = list(csv.DictReader((evidence / "canvas/frames.csv").open()))
assert len(rows) == 184
for scale in ("1.0", "1.25", "1.5", "2.0"):
    scoped = [r for r in rows if r["scale"] == scale]
    assert len(scoped) == 46
    for kind, count in (("cold", 1), ("warmup", 5), ("unchanged", 10), ("pan", 10), ("edit", 10), ("zoom", 10)):
        assert len([r for r in scoped if r["kind"] == kind]) == count
    for row in scoped:
        assert math.isfinite(float(row["milliseconds"])) and float(row["milliseconds"]) >= 0
        assert 0 < int(row["work"]) <= 64000000
        if row["kind"] == "unchanged":
            assert int(row["compiled"]) == int(row["upload_bytes"]) == 0
    pans = [r for r in scoped if r["kind"] == "pan"]
    cold = next(r for r in scoped if r["kind"] == "cold")
    assert all(r["cache_misses"] == cold["cache_misses"] for r in pans)
assert not read("canvas/summary.json")["qualified"]
assert not read("canvas/summary.json")["input_to_present"]

cancel = read("cancellation.json")
assert len(cancel["cpu_runs_JVM_then_Native"]) == 2
assert all(r["checks"] == 251 and r["work"] == 250 for r in cancel["cpu_runs_JVM_then_Native"])
assert len(cancel["worker_retire_ns"]) == 20 and cancel["cooperative_only"]
assert all(c["exit"] == 0 for c in read("controls.json"))
invocation = read("latency/20261029T014416.694586Z/invocation.json")
assert invocation["kind"] == "diagnostic" and invocation["status"] == "passed"
assert not invocation["qualified"]
package = read("package.json")["files"]
assert all(package[p] == digest for p, digest in invocation["package_sha256"].items() if p in package)
sys.path.insert(0, str(repo / "desktop/tools"))
from latency_receipt import validate

accepted, reason = validate(evidence / "latency/20261029T014416.694586Z/benchmark")
assert accepted, reason
summary = read("validation-summary.json")
assert sum(summary["by_scope"].values()) == summary["distinct_scala_test_cases"] == 749
print("Source/artifact hashes, all 16 pixel pairs, canvas counters and host diagnostic verified.")
print("Quiet qualification and spontaneous hardware-loss races remain open.")
