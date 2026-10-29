#!/usr/bin/env python3
"""One preserved camera diagnostic per packaged workload; available-host conditions."""
import datetime as dt
import hashlib
import json
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
from camera_receipt import validate

repo = Path(__file__).resolve().parents[2]
app = repo / "desktop/target/package/Sirin.app/Contents"
java = app / "runtime/Contents/Home/bin/java"
library = app / "app/libsirin_desktop.dylib"
files = [java, library, app / "MacOS/Sirin", *sorted((app / "app").glob("*.jar"))]
assert files and all(p.is_file() for p in files), "package first"
stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
output = repo / "desktop/target/camera-evidence" / stamp
output.mkdir(parents=True, exist_ok=False)
metadata = dict(platform=platform.platform(), kind="available-host camera diagnostic", quiet_qualified=False,
                package_sha256={str(p.relative_to(repo)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}, runs=[])
base = [str(java), "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED", f"-Dsirin.repo={repo}", f"-Dsirin.desktop.library={library}", "-cp", str(app / "app/*")]
jobs = [("baseline-workload", "sirin.desktop.CanvasBenchmark", [], "canvasDiagnostic", None),
        ("worker", "sirin.desktop.CameraMotionBenchmark", [], "cameraWorkerReceipt", "worker"),
        ("input", "sirin.desktop.DesktopWorkbench", ["canvas", "benchmark"], "cameraInputReceipt", "input"),
        ("canvas-controls", "sirin.desktop.DesktopWorkbench", ["canvas", "scenario"], None, None),
        ("workbench-recovery", "sirin.desktop.DesktopWorkbench", ["recovery"], None, None),
        ("native-failures", "sirin.desktop.WindowSpike", ["failures"], None, None)]
for name, main, args, marker, mode in jobs:
    folder = output / name
    folder.mkdir()
    snapshot = subprocess.run(["top", "-l", "2", "-n", "0", "-s", "1"], capture_output=True, text=True)
    (folder / "host-load.txt").write_text(snapshot.stdout + snapshot.stderr)
    command = base + [main] + args
    item = dict(name=name, command=command, status="running")
    metadata["runs"].append(item)
    (output / "invocation.json").write_text(json.dumps(metadata, indent=2) + "\n")
    with (folder / "stdout.log").open("w") as log:
        result = subprocess.run(command, cwd=repo, stdout=log, stderr=subprocess.STDOUT, timeout=150)
    item.update(exit_code=result.returncode, status="passed" if result.returncode == 0 else "failed")
    if marker:
        match = re.search(r"^" + marker + r"=(\S+)", (folder / "stdout.log").read_text(), re.MULTILINE)
        if match:
            receipt = Path(match[1]).resolve()
            assert receipt.is_relative_to((repo / "desktop/target").resolve())
            shutil.copytree(receipt, folder / "receipt")
        if mode and (folder / "receipt").is_dir():
            try:
                checked = validate(folder / "receipt", mode)
                (folder / "acceptance.json").write_text(json.dumps(checked, indent=2) + "\n")
                item["targets_passed"] = checked["passed"]
            except (AssertionError, ValueError, KeyError, OSError) as error:
                item.update(targets_passed=False, validation_error=str(error))
    (output / "invocation.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(item), flush=True)
print(f"cameraEvidence={output}")
sys.exit(0 if all(r["exit_code"] == 0 and r.get("targets_passed", True) for r in metadata["runs"]) else 1)
