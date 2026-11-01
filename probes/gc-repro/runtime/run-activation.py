#!/usr/bin/env python3
"""Run a frozen serial activation experiment with an inherited SIGUSR2 sentinel."""
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
p.add_argument("protocol", type=Path)
p.add_argument("inputs", type=Path)
p.add_argument("out", type=Path)
a = p.parse_args()
protocol = json.loads(a.protocol.read_text())
inputs = json.loads(a.inputs.read_text())
assert protocol["all_cells_block_SIGUSR2_before_exec"] is True
assert protocol["qualification"] is False
for cell in protocol["order"]:
    item = inputs[cell]
    actual = hashlib.sha256(Path(item["binary"]).read_bytes()).hexdigest()
    assert actual == item["sha256"], (cell, actual)
a.out.mkdir(parents=True, exist_ok=False)
results = []
for cell in protocol["order"]:
    item = inputs[cell]
    target = a.out / cell
    target.mkdir()
    env = dict(os.environ)
    env["GC_MAXIMUM_HEAP_SIZE"] = protocol["heap_cap"]
    env["SCALANATIVE_GC_SYNC_TIMEOUT_MS"] = str(protocol["sync_timeout_ms"])
    command = [item["binary"], *protocol["arguments"]]
    record = {
        "cell": cell,
        "command": command,
        "binary_sha256": item["sha256"],
        "start_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "load_start": os.getloadavg(),
        "timeout_seconds": protocol["external_timeout_seconds"],
        "environment": {k: v for k, v in env.items() if k.startswith(("GC_", "SCALANATIVE_GC_"))},
        "inherited_blocked_signal": "SIGUSR2",
    }
    start = time.monotonic()
    with (target / "stdout.log").open("w") as out, (target / "stderr.log").open("w") as err:
        prior_mask = signal.pthread_sigmask(signal.SIG_BLOCK, {signal.SIGUSR2})
        try:
            child = subprocess.Popen(command, env=env, stdout=out, stderr=err, start_new_session=True)
        finally:
            signal.pthread_sigmask(signal.SIG_SETMASK, prior_mask)
        record["pid"] = child.pid
        (target / "started.json").write_text(json.dumps(record, indent=2) + "\n")
        print(f"START {cell} pid={child.pid}", flush=True)
        try:
            child.wait(timeout=protocol["external_timeout_seconds"])
            record["timed_out"] = False
        except subprocess.TimeoutExpired:
            record["timed_out"] = True
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.wait()
    record.update(returncode=child.returncode, elapsed_seconds=time.monotonic() - start, load_end=os.getloadavg())
    (target / "result.json").write_text(json.dumps(record, indent=2) + "\n")
    results.append(record)
    print(f"END {cell} code={child.returncode} timeout={record['timed_out']}", flush=True)
(a.out / "results.json").write_text(json.dumps(results, indent=2) + "\n")
print("Series recorded; individual outcomes are not qualification.", flush=True)
