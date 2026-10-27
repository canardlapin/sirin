#!/usr/bin/env python3
"""Record a conservative macOS preflight; readiness does not qualify a soak."""
import argparse
import datetime
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--samples', type=int, default=6)
    parser.add_argument('--interval', type=float, default=10)
    parser.add_argument('--min-idle', type=float, default=80)
    parser.add_argument('--max-load-per-cpu', type=float, default=0.5)
    args = parser.parse_args()
    if platform.system() != 'Darwin':
        parser.error('this preflight currently supports macOS only')
    if args.samples < 2 or args.interval < 2 or not 0 <= args.min_idle <= 100 or args.max_load_per_cpu <= 0:
        parser.error('require >=2 samples, interval >=2 s, idle in [0,100], positive load threshold')
    args.out.mkdir(parents=True, exist_ok=False)
    cpus = os.cpu_count()
    if not cpus:
        raise RuntimeError('cannot determine logical CPU count')
    result = {'start_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'logical_cpus': cpus, 'thresholds': {'min_idle_percent': args.min_idle,
              'max_load_per_cpu': args.max_load_per_cpu}, 'samples': [],
              'ready': False, 'qualification': False}
    try:
        for index in range(args.samples):
            started = time.monotonic()
            sample = subprocess.run(['/usr/bin/top', '-l', '2', '-s', '2', '-n', '0'],
                                    capture_output=True, text=True, timeout=15, check=True)
            (args.out / f'top-{index + 1:02d}.log').write_text(sample.stdout + sample.stderr)
            # top's first CPU reading covers an earlier interval; use its second.
            values = re.findall(r'CPU usage:.*?([0-9.]+)% idle', sample.stdout)
            if len(values) != 2:
                raise RuntimeError('expected two CPU samples from top; cannot establish readiness')
            idle = float(values[-1])
            load = os.getloadavg()
            ready = idle >= args.min_idle and load[0] / cpus <= args.max_load_per_cpu
            record = {'utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                      'idle_percent': idle, 'load': load, 'ready': ready}
            result['samples'].append(record)
            print(json.dumps(record), flush=True)
            if index + 1 < args.samples:
                time.sleep(max(0, args.interval - (time.monotonic() - started)))
        result['ready'] = all(s['ready'] for s in result['samples'])
    except Exception as error:
        result['error'] = str(error)
    finally:
        result['end_utc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (args.out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'ready': result['ready'], 'qualification': False}), flush=True)
    return 1 if 'error' in result else 0 if result['ready'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
