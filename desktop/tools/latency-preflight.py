#!/usr/bin/env python3
"""One immutable three-sample macOS quiet preflight. No retry-until-pass loop."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path)
args = parser.parse_args()
stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
output = args.output or Path(__file__).resolve().parents[1] / "target" / "quiet-preflight" / stamp
output.mkdir(parents=True, exist_ok=False)
logical_cpus = os.cpu_count()
if sys.platform != "darwin" or not logical_cpus:
    raise SystemExit("This preflight requires macOS and a known logical CPU count.")
samples = []
for index in range(3):
    result = subprocess.run(["top", "-l", "2", "-s", "1", "-n", "0"], capture_output=True, text=True, check=True)
    (output / f"sample-{index + 1}.txt").write_text(result.stdout)
    idle = re.findall(r"CPU usage:.*?([\d.]+)% idle", result.stdout)
    load = re.findall(r"Load Avg:\s*([\d.]+),", result.stdout)
    if not idle or not load:
        raise SystemExit(f"Unrecognized top output retained at {output}; no passing receipt produced.")
    samples.append({"at_utc": dt.datetime.now(dt.timezone.utc).isoformat(), "cpu_idle_percent": float(idle[-1]), "load_1m": float(load[-1])})
    if index < 2:
        time.sleep(5)
accepted = all(s["cpu_idle_percent"] >= 85 and s["load_1m"] <= logical_cpus / 2 for s in samples)
receipt = {"protocol": "three_interval_samples_idle85_load_half_cpus_v1", "logical_cpus": logical_cpus, "samples": samples, "accepted": accepted, "output": str(output.resolve())}
(output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
print(json.dumps(receipt, indent=2))
raise SystemExit(0 if accepted else 2)
