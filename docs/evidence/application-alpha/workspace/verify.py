#!/usr/bin/env python3
"""Verify frozen workspace receipts; optional current-source/candidate comparison."""
from pathlib import Path
import hashlib,json,re,sys
base=Path(__file__).resolve().parent
repo=base.parents[3]
def read(name):
    return json.loads((base/name).read_text())
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
for name,expected in read('manifest.json')['files'].items():
    assert digest(base/name)==expected,name
full=read('full-checks-1/result.json')
assert len(full['results'])==2 and all(r['returncode']==0 for r in full['results'])
assert full['source_hashes']==read('full-checks-1/protocol.json')['source_hashes']
for name,expected in full['source_hashes'].items():
    assert digest(base/'full-checks-1/source'/name)==expected,name
counts=[tuple(map(int,x)) for x in re.findall(r'Passed: Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)',(base/'full-checks-1/checks-1.log').read_text())]
assert [x[0] for x in counts]==[69,16,2,6,191,34,22,44]*2
assert all(x[1:3]==(0,0) and x[0]==x[3] for x in counts)
assert 'Passed: Total 79, Failed 0, Errors 0, Passed 79' in (base/'full-checks-1/checks-2.log').read_text()
old=read('old-clips-control/result.json');successor=read('clip-replay-successor/result.json')
assert old['returncode']==1 and successor['returncode']==0
assert old['fixtures']==successor['fixtures'] and len(old['fixtures'])==4
for name,expected in old['fixtures'].items():
    assert digest(base/'old-clips-control'/name)==expected,name
assert digest(base/'old-clips-control/old-Layout.scala')==old['old_source_sha256']
assert digest(base/'old-clips-control/current-Layout.scala')==old['successor_source_sha256']==successor['source_sha256']==full['source_hashes']['host/src/main/scala/sirin/host/Layout.scala']
assert 'requirement failed: per-sample work budget' in (base/'old-clips-control/checks.log').read_text()
assert read('contracts-1/result.json')['returncode']==1
assert read('contracts-3/result.json')['returncode']==1
portable=read('workbench-portable.json')
assert portable['native_closure_valid'] and portable['codesign_deep_strict_valid'] and portable['library_loaded_without_UI']
ci=read('document-main-ci.json')
assert ci['headSha']==full['base_commit'] and ci['conclusion']=='success'
assert len(ci['jobs'])==9 and all(j['conclusion']=='success' for j in ci['jobs'])
summary=read('summary.json')
assert summary['cases']=={'JVM':384,'Native':384,'desktop':79}
assert summary['source_hashes']==full['source_hashes']
assert not summary['GUI_launched'] and not summary['native_workflow_qualified'] and not summary['alpha_complete']
if '--sources' in sys.argv:
    for name,expected in full['source_hashes'].items():
        assert digest(repo/name)==expected,name
if '--candidate' in sys.argv:
    candidate=read('launch-candidate.json')
    for name,expected in candidate['files'].items():
        assert digest(Path(candidate['app'])/name)==expected,name
print('Frozen workspace verified: 384 JVM + 384 Native + 79 desktop; exact failed/passing clip controls; native workflow and full alpha remain unqualified.')
