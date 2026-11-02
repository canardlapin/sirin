#!/usr/bin/env python3
"""Run the frozen P0.6 conditional candidate, with no readiness or assertion overrides."""
import argparse
import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys


BINARY_SHA256 = '87d7a7a94d48f21646053915415e31702517cf984a78b5b4f8c79287d2c2e5d9'
ENVIRONMENT = {'GC_MAXIMUM_HEAP_SIZE': '128M', 'SCALANATIVE_GC_SYNC_TIMEOUT_MS': '15000'}
TRIALS = 3


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def timestamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def read_json(path):
    return json.loads(path.read_text())


def ready(record):
    """Recheck actual samples, rather than trusting a ready flag alone."""
    samples = record['samples']
    cpus = record['logical_cpus']
    return (record.get('error') is None and record['ready'] is True and
            isinstance(cpus, int) and not isinstance(cpus, bool) and cpus > 0 and
            len(samples) == 6 and record['thresholds'] ==
            {'min_idle_percent': 80, 'max_load_per_cpu': 0.5} and
            all(sample['ready'] is True and 80 <= sample['idle_percent'] <= 100 and
                0 <= sample['load'][0] / cpus <= 0.5 for sample in samples))


def check_run(record, output, binary):
    """Exit zero alone cannot establish that the intended workload passed."""
    if (record['command'] != [str(binary), 'soak', '600', '4', '1000'] or
            record['binary_sha256'] != BINARY_SHA256 or record['returncode'] != 0 or
            record['timed_out'] is not False or record['timeout_seconds'] != 620 or
            record['environment'] != ENVIRONMENT):
        raise ValueError('Run receipt does not match the frozen successful workload')
    values = {}
    for line in output.splitlines():
        if not line.startswith('RESULT '):
            continue
        for key, value in re.findall(r'([\w.]+)=([^\s]+)', line[7:]):
            if key in values:
                raise ValueError('Duplicate result: ' + key)
            values[key] = value
    for key in ('soak.no_deadlock', 'soak.all_events_received',
                'soak.latency_max_below_50ms', 'soak.ticker_max_below_50ms'):
        if values.get(key) != 'PASS':
            raise ValueError('Missing or failed assertion: ' + key)
    if values.get('failures') != '0' or values.get('mode') != 'soak':
        raise ValueError('Missing successful probe termination')

    def number(key):
        value = float(values[key])
        if not math.isfinite(value) or value < 0:
            raise ValueError('Invalid metric: ' + key)
        return value

    if not 600 <= number('soak.seconds') < 620:
        raise ValueError('Wrong workload duration')
    if number('soak.workers') != 4 or number('soak.push_every_us') != 1000:
        raise ValueError('Wrong workload parameters')
    if number('soak.events_received') <= 0 or number('soak.alloc_iterations') <= 0:
        raise ValueError('Workload made no measurable progress')
    pushed = number('soak.events_pushed')
    if number('soak.push_failures') != 0 or number('soak.events_received') != pushed:
        raise ValueError('Events were lost or refused')
    # Assertions use unrounded nanoseconds. Printed milliseconds may round to 50.000.
    for key in ('soak.latency_max_ms', 'soak.ticker_max_oversleep_ms'):
        if number(key) > 50:
            raise ValueError('Latency exceeds the original bound')
    if not 0 < number('soak.gc_heap_mb') <= 128:
        raise ValueError('Heap capacity exceeds the configured cap')
    return values


def execute(binary, output):
    binary = binary.resolve(strict=True)
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        raise ValueError('This frozen candidate requires macOS arm64')
    if digest(binary) != BINARY_SHA256:
        raise ValueError('Binary does not match the frozen conditional candidate')
    output.mkdir(parents=True, exist_ok=False)
    source = Path(__file__).resolve().parent
    helpers = output / 'helpers'
    helpers.mkdir()
    for name in ('check-idle.py', 'run.py'):
        shutil.copy2(source / name, helpers / name)
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(('GC_', 'SCALANATIVE_GC_', 'DYLD_'))}
    environment.update(ENVIRONMENT)
    protocol = {'frozen_utc': timestamp(), 'binary': str(binary), 'binary_sha256': BINARY_SHA256,
                'trials': TRIALS, 'argv': ['soak', '600', '4', '1000'], 'timeout_seconds': 620,
                'preflight': {'samples': 6, 'interval': 10, 'min_idle': 80, 'max_load_per_cpu': 0.5},
                'environment': ENVIRONMENT, 'stop_on_first_failure_or_unavailable_host': True,
                'sources': {name: digest(helpers / name) for name in ('check-idle.py', 'run.py')},
                'runner_sha256': digest(Path(__file__)), 'runtime_adoption': False}
    (output / 'protocol.json').write_text(json.dumps(protocol, indent=2) + '\n')
    summary = {'started_utc': timestamp(), 'status': 'error', 'qualification': False, 'trials': []}
    try:
        for index in range(1, TRIALS + 1):
            if digest(binary) != BINARY_SHA256:
                raise ValueError('Candidate changed during qualification')
            trial = output / ('trial-%02d' % index)
            trial.mkdir()
            print('Preflight %d/%d' % (index, TRIALS), flush=True)
            with (trial / 'preflight.log').open('w') as log:
                flight = subprocess.run([sys.executable, str(helpers / 'check-idle.py'),
                                         '--out', str(trial / 'preflight')],
                                        stdout=log, stderr=subprocess.STDOUT, env=environment, timeout=120)
            entry = {'trial': index, 'preflight_returncode': flight.returncode, 'gate_started': False}
            summary['trials'].append(entry)
            if flight.returncode == 2:
                summary['status'] = 'host_unavailable'
                break
            if flight.returncode != 0 or not ready(read_json(trial / 'preflight/result.json')):
                raise ValueError('Preflight failed or has inconsistent readiness evidence')
            entry['gate_started'] = True
            print('Gate %d/%d: 600 seconds, four allocators, 1 ms events' % (index, TRIALS), flush=True)
            with (trial / 'gate.log').open('w') as log:
                gate = subprocess.run([sys.executable, str(helpers / 'run.py'), '--out', str(trial / 'gate'),
                                       '--timeout', '620', '--', str(binary), 'soak', '600', '4', '1000'],
                                      stdout=log, stderr=subprocess.STDOUT, env=environment)
            entry['returncode'] = gate.returncode
            if gate.returncode != 0:
                summary['status'] = 'gate_failed'
                break
            entry['metrics'] = check_run(read_json(trial / 'gate/result.json'),
                                         (trial / 'gate/stdout.log').read_text(), binary)
            if digest(binary) != BINARY_SHA256:
                raise ValueError('Candidate changed during a gate')
        else:
            summary['status'] = 'passed'
            summary['qualification'] = True
    except Exception as error:
        summary['status'] = 'error'
        summary['error'] = str(error)
    finally:
        summary['finished_utc'] = timestamp()
        (output / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary), flush=True)
    return 0 if summary['qualification'] else 2 if summary['status'] == 'host_unavailable' else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    return execute(args.binary, args.out.absolute())


if __name__ == '__main__':
    raise SystemExit(main())
