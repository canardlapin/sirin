#!/usr/bin/env python3
from pathlib import Path
import hashlib,json,re,sys
base=Path(__file__).resolve().parent
repo=base.parents[3]
for name,expected in json.loads((base/'manifest.json').read_text())['files'].items():
    assert hashlib.sha256((base/name).read_bytes()).hexdigest()==expected,name
result=json.loads((base/'full-checks/result.json').read_text())
assert len(result['results'])==2 and all(r['returncode']==0 for r in result['results'])
for name,expected in result['source_hashes'].items():
    assert hashlib.sha256((base/'full-checks/source'/name).read_bytes()).hexdigest()==expected,name
counts=[tuple(map(int,x)) for x in re.findall(r'Passed: Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)',(base/'full-checks/checks-1.log').read_text())]
assert [x[0] for x in counts]==[69,12,2,6,190,34,13,43]*2
assert all(x[1:3]==(0,0) and x[0]==x[3] for x in counts)
assert 'Passed: Total 69, Failed 0, Errors 0, Passed 69' in (base/'full-checks/checks-2.log').read_text()
assert json.loads((base/'history/targeted-1/result.json').read_text())['returncode']==1
assert json.loads((base/'history/targeted-2/result.json').read_text())['returncode']==0
summary=json.loads((base/'summary.json').read_text())
assert summary['cases']=={'JVM':369,'Native':369,'desktop':69}
assert not summary['GUI_launched'] and not summary['complete_visible_workbench'] and not summary['alpha_complete']
assert summary['source_hashes']==result['source_hashes']
ci=json.loads((base/'menu-main-ci.json').read_text())
assert ci['headSha']==result['base_commit'] and ci['conclusion']=='success'
assert len(ci['jobs'])==9 and all(j['conclusion']=='success' for j in ci['jobs'])
if '--sources' in sys.argv:
    for name,expected in result['source_hashes'].items():
        assert hashlib.sha256((repo/name).read_bytes()).hexdigest()==expected,name
print('Frozen document receipts verified: 369 JVM + 369 Native + 69 desktop; complete visible workbench and full alpha remain unfinished.')
