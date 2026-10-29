#!/usr/bin/env python3
"""Replay available-host acceptance; never relabel a run as quiet qualification."""
import csv
import hashlib
import json
from pathlib import Path
import subprocess
import sys

here = Path(__file__).resolve().parent
repo = here.parents[2]
summary = json.loads((here / "acceptance.json").read_text())
source_diff = subprocess.run(["git", "diff", "--quiet", "ada4e2b", "--", ".", ":(exclude)docs/**", ":(exclude).mote/**", ":(exclude)README.md", ":(exclude)desktop/README.md"], cwd=repo)
assert source_diff.returncode == 0, "Application/build inputs changed after the accepted checkpoint"
assert summary["available_host_accepted"] and not summary["formal_quiet_qualification"]
assert summary["selected_source_commit"] == "ada4e2b"
# Earlier frozen documentation remains immutable evidence at its source commit.
source = json.loads((repo / "docs/evidence/canvas-recovery-20261028/sources.json").read_text())["files"]
allowed_document_changes = {"desktop/README.md", "docs/design/VISION.md"}
for relative, expected in source.items():
    frozen = subprocess.check_output(["git", "show", f"ada4e2b:{relative}"], cwd=repo)
    assert hashlib.sha256(frozen).hexdigest() == expected, relative
    if relative not in allowed_document_changes:
        assert hashlib.sha256((repo / relative).read_bytes()).hexdigest() == expected, relative
for relative, expected in json.loads((here / "package.json").read_text()).items():
    assert hashlib.sha256((repo / relative).read_bytes()).hexdigest() == expected, relative
sys.path.insert(0, str(repo / "desktop/tools"))
from latency_receipt import validate
for run in summary["runs"]:
    folder = here / run["label"]
    invocation = json.loads((folder / "invocation.json").read_text())
    assert invocation["exit_code"] == 0 and invocation["status"] == "passed"
    assert not invocation["qualified"] and invocation["receipt_validated"]
    accepted, explanation = validate(folder / "benchmark")
    assert accepted, explanation
    rows = list(csv.DictReader((folder / "benchmark/input-to-present.csv").open()))
    for kind in ("action", "scroll"):
        values = sorted(float(r["milliseconds"]) for r in rows if r["phase"] == "measured" and r["kind"] == kind)
        result = run["metrics"][kind]
        assert len(values) == result["n"] == 100
        assert [values[49], values[94], values[98], values[-1]] == [result[k] for k in ("p50_ms", "p95_ms", "p99_ms", "max_ms")]
        assert values[94] <= result["p95_limit_ms"] and values[98] <= result["p99_limit_ms"]
for line in (here / "evidence.sha256").read_text().splitlines():
    expected, relative = line.split("  ", 1)
    assert hashlib.sha256((here / relative).read_bytes()).hexdigest() == expected, relative
print("Both frozen runs pass unchanged geometry, sample and percentile checks.")
print("Retained-GPU source/package identity and acceptance artifacts verified.")
print("Available-host gate accepted under owner waiver; no formal quiet-run claim.")
