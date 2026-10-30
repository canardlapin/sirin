#!/usr/bin/env python3
"""Frozen integrity and denominators; never substitutes for execution."""
from pathlib import Path
import hashlib,json,re,sys
base=Path(__file__).resolve().parent
repo=base.parents[3]
manifest=json.loads((base/'manifest.json').read_text())
for name,expected in manifest['files'].items():
    assert hashlib.sha256((base/name).read_bytes()).hexdigest()==expected,name
result=json.loads((base/'validation/result.json').read_text())
assert len(result['results'])==2 and all(x['returncode']==0 for x in result['results'])
log=(base/'validation/checks-1.log').read_text()
counts=[tuple(map(int,x)) for x in re.findall(r'Passed: Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)',log)]
assert [x[0] for x in counts]==[69,9,2,6,190,34,7,25]*2
assert all(x[1:3]==(0,0) and x[0]==x[3] for x in counts)
assert log.count('Test run sirin.engine.WindowLifecycleSuite finished: 0 failed, 0 ignored, 14 total')==2
assert log.count('Test run sirin.engine.WindowKeySafetySuite finished: 0 failed, 0 ignored, 1 total')==2
assert 'Passed: Total 50, Failed 0, Errors 0, Passed 50' in (base/'validation/checks-2.log').read_text()
summary=json.loads((base/'summary.json').read_text())
assert summary['cases']=={'JVM':342,'Native':342,'desktop':50}
assert not summary['alpha_complete'] and not summary['native_ui_qualified']
for name in ['application-close-negative','key-equality-negative']:
    assert json.loads((base/'history'/name/'result.json').read_text())['returncode']!=0
if '--sources' in sys.argv:
    for name,expected in result['source_hashes'].items():
        assert hashlib.sha256((repo/name).read_bytes()).hexdigest()==expected,name
print('Frozen receipts verified: 342 JVM + 342 Native + 50 desktop; native UI and alpha remain unqualified.')
