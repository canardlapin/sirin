#!/usr/bin/env python3
"""Replay saved integrity and test receipts; never executes an app or GPU."""
import hashlib
import json
from pathlib import Path
import re

root = Path(__file__).resolve().parents[3]
evidence = Path(__file__).resolve().parent
manifest = json.loads((evidence / 'manifest.json').read_text())
for section in ('sources', 'evidence'):
    for name, expected in manifest[section].items():
        path = root / name
        assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, path

def totals(name):
    return [int(n) for n in re.findall(r'Passed: Total (\d+)', (evidence / name).read_text())]

assert totals('history/editor-accesskit-full-1.log')[:8] == [69,9,2,6,175,30,7,25]
assert totals('validation/editor-accesskit-native-refresh.log') == [69,9,2,6,175,30,7,25]
assert totals('validation/editor-accesskit-full-2.log') == [2,7,25,7,12,40,34,50]
assert totals('validation/editor-accesskit-final-package-2.log') == [13]
assert sum(totals('history/editor-accesskit-full-1.log')[:8])*2 + sum([7,12,40,34,50]) == 789
assert 'AccessKit native protocol:' in (evidence/'validation/editor-accesskit-protocol-3.log').read_text()
assert '\nOK\n' in (evidence/'validation/editor-python-tests.log').read_text()
assert '-lwgpu_native' not in (evidence/'validation/dynamic-libraries.txt').read_text()
assert '/libwgpu_native' not in (evidence/'validation/dynamic-libraries.txt').read_text()
assert manifest['native_ui_validation'] == 'pending_launch_approval'
print('Saved source/evidence hashes and test receipts verified; native UI acceptance remains pending.')
