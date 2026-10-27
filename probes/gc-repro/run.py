#!/usr/bin/env python3
"""Run one bounded experiment; preserve logs, environment knobs, and exit status."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--out", required=True, type=Path)
p.add_argument("--timeout", type=float, default=90)
p.add_argument("command", nargs=argparse.REMAINDER)
a = p.parse_args()
command = a.command[1:] if a.command[:1] == ["--"] else a.command
if not command or a.timeout <= 0:
    p.error("supply a command and positive timeout")
a.out.mkdir(parents=True, exist_ok=False)
record = {"command": command, "cwd": os.getcwd(), "start_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(), "load_start": os.getloadavg(), "timeout_seconds": a.timeout, "environment": {k: v for k, v in os.environ.items() if k.startswith(("GC_", "SCALANATIVE_GC_"))}}
record["binary_sha256"] = hashlib.sha256(Path(command[0]).read_bytes()).hexdigest()
start = time.monotonic()
with (a.out / "stdout.log").open("w") as out, (a.out / "stderr.log").open("w") as err:
    proc = subprocess.Popen(command, stdout=out, stderr=err, start_new_session=True)
    record["pid"] = proc.pid
    print(f"START pid={proc.pid} out={a.out}", flush=True)
    try:
        proc.wait(timeout=a.timeout)
        record["timed_out"] = False
    except subprocess.TimeoutExpired:
        record["timed_out"] = True
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        proc.wait()
record.update(returncode=proc.returncode, elapsed_seconds=time.monotonic() - start, load_end=os.getloadavg())
(a.out / "result.json").write_text(json.dumps(record, indent=2) + "\n")
print(json.dumps(record), flush=True)
print((a.out / "stdout.log").read_text()[-5000:])
print((a.out / "stderr.log").read_text()[-5000:])
raise SystemExit(124 if record["timed_out"] else (proc.returncode if proc.returncode >= 0 else 128 - proc.returncode))
