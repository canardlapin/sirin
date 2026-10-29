#!/usr/bin/env python3
"""Verify frozen evidence and bounded outcomes; launches no runtime, UI or GPU."""
import hashlib
import json
from pathlib import Path
import re

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
read = lambda name: json.loads((BASE / name).read_text())
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = read("manifest.json")
files = {str(p.relative_to(BASE)) for p in BASE.rglob("*") if p.is_file() and p.name != "manifest.json"}
assert files == set(manifest["evidence_files"]), "evidence file set changed"
for name, expected in manifest["evidence_files"].items():
    assert sha(BASE / name) == expected, name
for name, expected in manifest["source_files"].items():
    assert sha(ROOT / name) == expected, name
assert manifest["qualification"] is False

binaries = {}
attempts = 0
def trial(name, seconds, workers, expected, binary=None):
    global attempts
    r = read(name + "/result.json")
    assert r["command"][1:] == [str(seconds), str(workers), "2048", "true"], name
    assert r["environment"]["GC_MAXIMUM_HEAP_SIZE"] == "128M", name
    assert r["environment"]["SCALANATIVE_GC_SYNC_TIMEOUT_MS"] == "15000", name
    assert r["timeout_seconds"] == seconds + 15, name
    assert r["returncode"] == expected and r["timed_out"] is False, name
    if binary:
        assert r["binary_sha256"] == binary, name
    stdout = (BASE/name/"stdout.log").read_text()
    stderr = (BASE/name/"stderr.log").read_text()
    if expected == 0:
        assert "DONE elapsed_ms=" in stdout and "RESULT live_payload_bytes=" in stdout, name
        assert "FATAL:" not in stderr, name
    else:
        assert expected == -6 and "FATAL: Timeout after 15.0s" in stderr, name
        assert "DONE elapsed_ms=" not in stdout, name
    binaries[r["command"][0]] = r["binary_sha256"]
    attempts += 1
    return stdout, stderr

protocol = read("matrix-manifest.json")
assert protocol["trials_per_variant"] == 3
assert protocol["arguments"] == ["60", "1", "2048", "true"]
rows = read("matrix-result.json")
order = [f"{variant}-{i}" for i in range(1,4) for variant in
         ["stock-trap", "pinned-trap", "stock-conditional", "pinned-conditional"]]
assert [r["case"] for r in rows] == order
for r in rows:
    variant = r["case"].rsplit("-",1)[0]
    expected = -6 if variant.endswith("-trap") else 0
    assert r["runner_exit"] == (134 if expected == -6 else 0)
    trial("matrix/"+r["case"],60,1,expected,protocol["variants"][variant]["sha256"])
for group, outcomes in [("diagnostic",[0,-6,0]), ("mask-diagnostic",[-6,-6,0])]:
    rows = read(group+"-result.json")
    assert [r["runner_exit"] for r in rows] == [134 if n == -6 else 0 for n in outcomes]
    for i, expected in enumerate(outcomes,1):
        _, stderr = trial(f"{group}/pinned-trap-{i}",60,1,expected)
        if expected == -6:
            assert "SIRIN_DIAG" in stderr
            if group == "mask-diagnostic":
                assert "admitBus=1 admitSegv=0 yieldBus=1" in stderr
sample = read("diagnostic/pinned-trap-2/sample-attachment.json")
assert (BASE/"diagnostic/pinned-trap-2/native-stall.sample.txt").is_file()
nodefer = read("nodefer-protocol.json")
rows = read("nodefer-result.json")
assert [r["case"] for r in rows] == nodefer["order"]
for row in rows:
    expected = -6 if row["case"].startswith("nodefer-") else 0
    assert row["runner_exit"] == (134 if expected == -6 else 0)
    trial("nodefer-control/"+row["case"],60,1,expected)
stdout,_ = trial("validation/pinned-conditional-w4-120",120,4,0)
capacities = [int(n) for n in re.findall(r"SAMPLE .*?heap_bytes=(\d+)",stdout)]
assert len(capacities) == 24 and set(capacities) == {35790848}
assert "live_payload_bytes=4350976" in stdout
assert attempts == 24

idle = read("idle-preflight/result.json")
assert idle["thresholds"] == {"min_idle_percent":80,"max_load_per_cpu":0.5}
assert len(idle["samples"]) == 6 and not idle["ready"] and not idle["qualification"]
assert all(not sample["ready"] for sample in idle["samples"])
status = read("status.json")
assert status["event_600s_50ms_gate"]["status"] == "not-run"
assert not status["production_runtime_changed"] and not status["causal_control_accepted"]
assert not status["gc_issue_closed"] and not status["upstream_report_posted"]

scenario = read("editor-scenario/result.json")
assert scenario["returncode"] == 0 and not scenario["timed_out"]
assert "editorScenario=9 presentations=8 recoveries=1 rejected=0" in (BASE/"editor-scenario/stdout.log").read_text()
assert (BASE/"editor-scenario/stderr.log").read_text().count("nativeIME focus=1 active=1") == 2
binaries[scenario["command"][0]] = scenario["binary_sha256"]
cfg = read("editor-repeat/configuration.json")
assert read("editor-repeat/configuration-restored.json")["sha256"] == cfg["original_sha256"]
assert sha(BASE/"editor-repeat/original-SirinEdit.cfg") == cfg["original_sha256"]

integration = "integration/tranche-validation-20261029-v2/"
r = read(integration+"result.json")
assert r["returncode"] == 0 and not r["timed_out"] and r["source_commit"] == "9372ca5"
counts = [int(n) for n in re.findall(r"Passed: Total (\d+)",(BASE/integration/"checks.log").read_text())]
alias = [69,9,2,6,175,30,7,25]
assert counts == alias + alias + [50] and sum(counts) == 696
assert read("integration/tranche-validation-20261029/setup-failure.json")["tests_executed"] is False
assert read("integration/accepted-package-editor-verifier.json")["returncode"] == 0
assert "8 local artifacts matched" in (BASE/"integration/accepted-package-editor-verifier.log").read_text()
# Preserve the historical artifact contract instead of silently accepting a rebuild.
assert read("integration/integration-source-editor-verifier.json")["returncode"] == 1
assert "AssertionError: desktop/target/native/libsirin_desktop.dylib" in (BASE/"integration/integration-source-editor-verifier.log").read_text()
source = read("integration/integrated-editor-source-hashes.json")
for name, expected in source["matched_files"].items():
    assert sha(ROOT/name) == expected, name
comparison = read("integration/rebuilt-artifact-comparison.json")
assert len(comparison["matching_sections"]) == 31 and comparison["different_sections"] == []
assert comparison["accepted"]["sha256"] != comparison["rebuilt"]["sha256"]
for label in ["accepted","rebuilt"]:
    p = Path(comparison[label]["path"])
    if p.is_file(): assert sha(p) == comparison[label]["sha256"], str(p)

available = 0
for path, expected in binaries.items():
    p = Path(path)
    if p.is_file():
        assert sha(p) == expected, path
        available += 1
for name in ["trap-diagnostic","trap-mask-diagnostic","trap-nodefer-control"]:
    assert read("runtime-replay.json")["variants"][name] == read("runtime/"+name+".json")["output_sha256"]
print(f"Verified {len(files)} frozen files, {attempts} allocation attempts +1 editor scenario, "
      f"696 fresh Scala checks and {available} available binaries. "
      "Failed controls/package-identity check retained; long GC gate not run; runtime policy unchanged.")
