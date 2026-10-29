#!/usr/bin/env python3
"""Replay sealed source/artifact statistics and frozen diagnostic acceptance."""
import csv
import hashlib
import json
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
assert len(pixels) == 49
assert {p["scope"] for p in pixels} == {"mixed", "dense", "original", "edit"}
assert sum(p["oracle_excluded"] for p in pixels) == 1832
for item in pixels:
    actual = (evidence / item["actual"]).read_bytes()
    reference = (evidence / item["reference"]).read_bytes()
    assert len(actual) == len(reference) == item["width"] * item["height"] * 4
    worst = max(abs(a - b) for a, b in zip(actual, reference))
    assert worst == item["all_byte_worst"]
    assert item["oracle_checked"] + item["oracle_excluded"] == item["width"] * item["height"]
    assert item["oracle_checked"] > item["width"] * item["height"] / 2
    assert item["oracle_worst"] <= 2 and item["oracle_mismatches"] == 0
    if item["scope"] in ("dense", "edit"):
        assert worst == 0
    # The Scala oracle establishes positions, independently of renderer errors.
    # This integrity replay preserves its receipt and computes necessary raw counts.
    bad_pixels = sum(any(abs(a - b) > 2 for a, b in zip(actual[i:i+4], reference[i:i+4])) for i in range(0, len(actual), 4))
    assert bad_pixels <= item["oracle_excluded"]

sys.path.insert(0, str(repo / "desktop/tools"))
from camera_receipt import validate as camera_validate
from latency_receipt import validate as host_validate

for mode in ("worker", "input"):
    observed = camera_validate(evidence / "measured" / mode / "receipt", mode)
    assert observed == read("measured/" + mode + "/acceptance.json")
    assert observed["passed"] and not observed["quiet_qualified"]
baseline = list(csv.DictReader((evidence / "measured/baseline-workload/receipt/frames.csv").open()))
assert len(baseline) == 184
for scale in ("1.0", "1.25", "1.5", "2.0"):
    assert len([r for r in baseline if r["scale"] == scale]) == 46
metadata = read("measured/invocation.json")
assert len(metadata["runs"]) == 6 and all(r["exit_code"] == 0 for r in metadata["runs"])
assert not metadata["quiet_qualified"]
failed = read("history/failed-dense-package/invocation.json")
assert any(r["name"] == "worker" and r["status"] == "failed" for r in failed["runs"])
assert failed["package_sha256"] != metadata["package_sha256"]
host = read("host-regression/invocation.json")
assert host["status"] == "passed" and host["kind"] == "diagnostic" and not host["qualified"]
accepted, reason = host_validate(evidence / "host-regression/benchmark")
assert accepted, reason
for path, digest in host["package_sha256"].items():
    assert metadata["package_sha256"][path] == digest
for path, digest in metadata["package_sha256"].items():
    if (repo / path).exists():
        assert hashlib.sha256((repo / path).read_bytes()).hexdigest() == digest, path
summary = read("validation-summary.json")
assert sum(summary["by_scope"].values()) == summary["distinct_scala_cases"] == 756
assert len(read("negative-controls.json")) == 3 and all(c["rejected"] for c in read("negative-controls.json"))
print("Final sources/artifacts, 49 raw statistics, frozen warm counters/latencies and host regression verified.")
print("Original boundary policy retained; novel-scale rebuilds and spontaneous-loss limits remain explicit.")
