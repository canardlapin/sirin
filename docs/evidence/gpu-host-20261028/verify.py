#!/usr/bin/env python3
"""Verify retained evidence integrity and the final diagnostic; no qualification claim."""
import hashlib
import json
from pathlib import Path
import sys

evidence = Path(__file__).resolve().parent
repo = evidence.parents[2]
for relative, expected in json.loads((evidence / "sources.json").read_text())["files"].items():
    assert hashlib.sha256((repo / relative).read_bytes()).hexdigest() == expected, relative
for relative, expected in json.loads((evidence / "artifacts.json").read_text()).items():
    assert hashlib.sha256((evidence / relative).read_bytes()).hexdigest() == expected, relative
for item in json.loads((evidence / "pixels.json").read_text()):
    suffix = f'{item["width"]}x{item["height"]}.rgba'
    reference = (evidence / "pixels" / f"reference-{suffix}").read_bytes()
    assert len(reference) == item["width"] * item["height"] * 4
    for name in ("batch-tiles", "presentation"):
        actual = (evidence / "pixels" / f"{name}-{suffix}").read_bytes()
        assert len(actual) == len(reference)
        worst = max(abs(a-b) for a, b in zip(actual, reference))
        assert worst == item[name]["max_byte_error"] and worst <= 2
sys.path.insert(0, str(repo / "desktop/tools"))
from latency_receipt import validate

history = json.loads((evidence / "latency-history.json").read_text())
assert len([r for r in history if r["kind"] == "qualification"]) == 1
for item in history:
    invocation = json.loads((evidence / "latency" / item["run"] / "invocation.json").read_text())
    assert not invocation["qualified"]
    if item["kind"] == "qualification":
        assert invocation["status"] == "not_run" and not invocation["quiet_preflight_passed"]
        assert not (evidence / "latency" / item["run"] / "benchmark").exists()
latest = history[-1]
assert latest["kind"] == "diagnostic" and latest["status"] == "passed"
accepted, reason = validate(evidence / "latency" / latest["run"] / "benchmark")
assert accepted, reason
summary = json.loads((evidence / "validation-summary.json").read_text())
assert sum(summary["by_scope"].values()) == summary["distinct_scala_test_cases"] == 735
print("Sources, retained artifact hashes, four-scale pixels and final diagnostic verified.")
print("Quiet qualification remains open; no diagnostic is a qualified result.")
