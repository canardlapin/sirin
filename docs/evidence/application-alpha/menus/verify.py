#!/usr/bin/env python3
from pathlib import Path
import hashlib,json,re,sys
base=Path(__file__).resolve().parent
repo=base.parents[3]
manifest=json.loads((base/'manifest.json').read_text())
for name,expected in manifest['files'].items():
    assert hashlib.sha256((base/name).read_bytes()).hexdigest()==expected,name
full=json.loads((base/'full-checks/result.json').read_text())
assert len(full['results'])==2 and all(result['returncode']==0 for result in full['results'])
successor=json.loads((base/'key-encoding-successor/result.json').read_text())
assert successor['returncode']==0
for folder,result in [('full-checks',full),('key-encoding-successor',successor)]:
    for name,expected in result['source_hashes'].items():
        assert hashlib.sha256((base/folder/'source'/name).read_bytes()).hexdigest()==expected,(folder,name)
changed=[p for p,h in successor['source_hashes'].items() if h!=full['source_hashes'][p]]
assert changed==successor['changed_since_full']
assert all(p.startswith('desktop/') or p.startswith('docs/') for p in changed)
counts=[tuple(map(int,x)) for x in re.findall(r'Passed: Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)',(base/'full-checks/checks-1.log').read_text())]
expected=[69,12,2,6,190,34,7,43]*2
assert [x[0] for x in counts]==expected
assert all(x[1:3]==(0,0) and x[0]==x[3] for x in counts)
assert 'Passed: Total 60, Failed 0, Errors 0, Passed 60' in (base/'full-checks/checks-2.log').read_text()
assert 'Passed: Total 61, Failed 0, Errors 0, Passed 61' in (base/'key-encoding-successor/checks.log').read_text()
assert json.loads((base/'history/full-checks-1/result.json').read_text())['results'][0]['returncode']==1
summary=json.loads((base/'summary.json').read_text())
assert summary['cases']=={'JVM':363,'Native':363,'desktop':61}
assert not summary['GUI_launched'] and not summary['native_menu_operation_qualified'] and not summary['alpha_complete']
assert summary['current_source_hashes']==successor['source_hashes']
ci=json.loads((base/'previous-host-ci.json').read_text())
assert ci['headSha']==full['base_commit'] and ci['conclusion']=='success'
assert len(ci['jobs'])==9 and all(j['conclusion']=='success' for j in ci['jobs'])
candidate=json.loads((base/'frozen-launch-candidate-check.json').read_text())
assert candidate['matches_frozen_candidate'] and not candidate['launched']
if '--sources' in sys.argv:
    for name,expected in summary['current_source_hashes'].items():
        assert hashlib.sha256((repo/name).read_bytes()).hexdigest()==expected,name
if '--candidate' in sys.argv:
    for name,expected in candidate['files'].items():
        assert hashlib.sha256((Path(candidate['candidate'])/name).read_bytes()).hexdigest()==expected,name
print('Frozen menu receipts verified: 363 JVM + 363 Native + 61 desktop; actual native menus and full alpha remain unqualified.')
