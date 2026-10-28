#!/usr/bin/env python3
"""Run one packaged JVM latency experiment; qualification requires quiet preflight."""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
from latency_receipt import validate

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--diagnostic", action="store_true", help="Record a diagnostic even if quiet preflight fails; never mark it qualified.")
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
app = repo / "desktop/target/package/Sirin.app/Contents"
java = app / "runtime/Contents/Home/bin/java"
jar = app / "app/sirin.jar"
library = app / "app/libsirin_desktop.dylib"
for path in [java, jar, library]:
    if not path.is_file():
        raise SystemExit(f"Package the development app first; missing {path}")
stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
output = repo / "desktop/target/latency-runs" / stamp
output.mkdir(parents=True, exist_ok=False)
files = [java, library, *sorted((app / "app").glob("*.jar"))]
metadata = {"kind": "diagnostic" if args.diagnostic else "qualification", "package_sha256": {str(p.relative_to(repo)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}, "quiet_preflight_passed": False, "status": "not_run", "qualified": False}
(output / "invocation.json").write_text(json.dumps(metadata, indent=2) + "\n")
preflight = subprocess.run([sys.executable, str(Path(__file__).with_name("latency-preflight.py")), "--output", str(output / "preflight")], capture_output=True, text=True)
(output / "preflight.log").write_text(preflight.stdout + preflight.stderr)
preflight_receipt = output / "preflight/receipt.json"
if preflight.returncode not in (0, 2) or not preflight_receipt.is_file():
    raise SystemExit(f"Preflight failed to produce a valid receipt; retained at {output}")
metadata["quiet_preflight_passed"] = preflight.returncode == 0 and json.loads(preflight_receipt.read_text())["accepted"]
if not metadata["quiet_preflight_passed"] and not args.diagnostic:
    (output / "invocation.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps({"output": str(output), **metadata}, indent=2))
    raise SystemExit(2)
command = [str(java), "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED", f"-Dsirin.repo={repo}", f"-Dsirin.desktop.library={library}", "-cp", str(app / "app/*"), "sirin.desktop.DesktopWorkbench", "benchmark"]
metadata["command"] = command
with (output / "benchmark.log").open("w") as log:
    result = subprocess.run(command, cwd=repo, stdout=log, stderr=subprocess.STDOUT)
metadata["exit_code"] = result.returncode
metadata["status"] = "passed" if result.returncode == 0 else "failed"
log = (output / "benchmark.log").read_text()
match = re.search(r"^benchmarkReceipt=(.+) status=", log, re.MULTILINE)
if match:
    receipt = Path(match[1]).resolve()
    # Only copy the generated local receipt path supplied by this process.
    if receipt.parent == (repo / "desktop/target/benchmarks").resolve() and receipt.is_dir():
        shutil.copytree(receipt, output / "benchmark")
accepted, explanation = validate(output / "benchmark")
metadata["receipt_validated"] = accepted
metadata["receipt_validation"] = explanation
metadata["qualified"] = metadata["quiet_preflight_passed"] and not args.diagnostic and result.returncode == 0 and accepted
if result.returncode == 0 and not accepted:
    metadata["status"] = "failed"
(output / "invocation.json").write_text(json.dumps(metadata, indent=2) + "\n")
print(json.dumps({"output": str(output), **metadata}, indent=2))
raise SystemExit(result.returncode if result.returncode != 0 else 0 if accepted else 2)
