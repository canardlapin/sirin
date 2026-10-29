#!/usr/bin/env python3
"""Replay immutable initial and successor receipts without launching UI or GPU."""
import csv
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[3]
evidence = Path(__file__).resolve().parent
successor = evidence / 'native-acceptance'
initial = json.loads((evidence / 'manifest.json').read_text())
current = json.loads((successor / 'manifest.json').read_text())
digest = lambda value: hashlib.sha256(value).hexdigest()
# The pending-approval receipt remains a claim about its exact original commit.
for section in ('sources', 'evidence'):
    for name, expected in initial[section].items():
        blob = subprocess.check_output(['git', 'show', f'{current["initial_snapshot_commit"]}:{name}'], cwd=root)
        assert digest(blob) == expected, (section, name)
for section in ('sources', 'evidence'):
    for name, expected in current[section].items():
        assert digest((root/name).read_bytes()) == expected, (section, name)
checked = 0
for name, expected in current['artifacts'].items():
    path = root/name
    if path.is_file():
        assert digest(path.read_bytes()) == expected, name
        checked += 1

def totals(path):
    return [int(n) for n in re.findall(r'Passed: Total (\d+)', path.read_text())]

aliases = [69,9,2,6,175,30,7,25]
assert totals(successor/'final-matrix.log') == aliases + aliases + [50]
assert totals(evidence/'validation/editor-accesskit-full-2.log')[3:] == [7,12,40,34,50]
assert sum(aliases)*2 + sum([7,12,40,34,50]) == 789
scenario = (successor/'final-editor-scenario.log').read_text()
assert 'editorScenario=9 presentations=8 recoveries=1 rejected=0' in scenario
assert scenario.count('nativeIME focus=1 active=1') == 2
assert scenario.count('nativeIME client=SDL3TranslatorResponder keyWindow=1') == 2
assert 'AccessKit native protocol:' in (successor/'final-protocol.log').read_text()
assert '\nOK\n' in (successor/'final-python.log').read_text()
sys.path.insert(0, str(root/'desktop/tools'))
from latency_receipt import validate
passed, reason = validate(successor/'host-regression/benchmark')
assert passed, reason
invocation = json.loads((successor/'host-regression/invocation.json').read_text())
assert invocation['exit_code'] == 0 and invocation['receipt_validated']
assert invocation['qualified'] is False
failed = json.loads((successor/'history/host-regression-1/invocation.json').read_text())
assert failed['exit_code'] == 1 and failed['receipt_validated'] is False
assert not validate(successor/'history/host-regression-1/benchmark')[0]
rows = list(csv.DictReader((successor/'host-regression/benchmark/input-to-present.csv').open()))
summary = json.loads((successor/'host-summary.json').read_text())
for kind in ('action','scroll'):
    values = sorted(float(row['milliseconds']) for row in rows if row['phase']=='measured' and row['kind']==kind)
    assert summary[kind] == dict(n=100,p50_ms=values[49],p95_ms=values[94],p99_ms=values[98],max_ms=values[-1])
assert current['native_ui_validation'] == 'bounded_macos_jvm_passed'
assert current['formal_quiet_qualification'] is False
assert current['open_qualification_bead'] == 'bd-01M64NKHTDEJ7WZGQBTA6SMF23'
print(f'Initial/successor hashes,789 Scala cases,native controls and host diagnostic verified;{checked} local artifacts matched. Real IME/VoiceOver/drag qualification remains open.')
