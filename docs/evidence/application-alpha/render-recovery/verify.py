#!/usr/bin/env python3
"""Verify frozen nonfatal render-refusal recovery, without asserting scene/native parity."""
from pathlib import Path
import hashlib,json,re,sys
base=Path(__file__).resolve().parent
repo=base.parents[3]
def read(name):return json.loads((base/name).read_text())
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
for name,expected in read('manifest.json')['files'].items():
    assert digest(base/name)==expected,name
full=read('full-checks-1/result.json')
assert len(full['results'])==2 and all(r['returncode']==0 for r in full['results'])
assert full['source_mismatches']==[]
assert full['source_hashes']==read('full-checks-1/protocol.json')['source_hashes']
for name,expected in full['source_hashes'].items():
    assert digest(base/'full-checks-1/source'/name)==expected,name
counts=[tuple(map(int,x)) for x in re.findall(r'Passed: Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)',(base/'full-checks-1/checks-1.log').read_text())]
assert [x[0] for x in counts]==[69,16,2,6,196,34,24,47]*2
assert all(x[1:3]==(0,0) and x[0]==x[3] for x in counts)
shared=(base/'full-checks-1/checks-1.log').read_text()
for suite,total in [('sirin.engine.WindowRenderRecoverySuite',5),('sirin.host.WindowCaptureSuite',7),('sirin.specimen.DocumentWorkspaceSuite',10)]:
    assert shared.count(f'Test run {suite} finished: 0 failed, 0 ignored, {total} total')==2,suite
assert 'Passed: Total 89, Failed 0, Errors 0, Passed 89' in (base/'full-checks-1/checks-2.log').read_text()
old=read('undeclared-menu-control/result.json');new=read('declared-menu-successor/result.json')
assert old['returncode']==1 and new['returncode']==0 and old['test_sha256']==new['test_sha256']
for mode,result in [('undeclared-menu-control',old),('declared-menu-successor',new)]:
    assert digest(base/mode/'Reconciliation.scala')==result['source_sha256']
    assert digest(base/mode/'WindowCaptureSuite.scala')==result['test_sha256']
assert new['test_sha256']==full['source_hashes']['host/src/test/scala/sirin/host/WindowCaptureSuite.scala']
assert 'Failed: Total 7, Failed 1, Errors 0, Passed 6' in (base/'undeclared-menu-control/checks.log').read_text()
assert 'Passed: Total 7, Failed 0, Errors 0, Passed 7' in (base/'declared-menu-successor/checks.log').read_text()
assert read('shared-2/result.json')['returncode']==1
assert read('contracts-1/result.json')['returncode']==1
assert read('contracts-2/result.json')['returncode']==1
assert read('contracts-3/result.json')['returncode']==1
assert read('routing-diagnostic/result.json')['returncode']==1
assert read('contracts-4/result.json')['returncode']==0
assert read('input-epoch-successor/result.json')['returncode']==0
assert read('shortcut-successor/result.json')['returncode']==0
source=(base/'full-checks-1/source/desktop/src/test/scala/sirin/desktop/WindowRenderRecoverySuite.scala').read_text()
for claim in ['paragraph exceeds shaping work budget','retained draft','state(field).canUndo','inputs.accepts','NativeApplication.liveApplications(),0']:
    assert claim in source,claim
ci=read('field-feedback-main-ci.json')
assert ci['headSha']==full['base_commit'] and ci['conclusion']=='success'
assert len(ci['jobs'])==9 and all(j['conclusion']=='success' for j in ci['jobs'])
assert read('historical-source-audit.json')['snapshot_hash_mismatches']=={}
summary=read('summary.json')
assert summary['cases']=={'JVM':394,'Native':394,'desktop':89}
assert summary['source_hashes']==full['source_hashes'] and summary['explicit_recovery_verified']
assert not summary['GUI_launched'] and not summary['native_client_qualified'] and not summary['original_scene_rendering_qualified'] and not summary['alpha_complete']
if '--sources' in sys.argv:
    for name,expected in full['source_hashes'].items():
        assert digest(repo/name)==expected,name
    for name,expected in read('unchanged-rendering-sources.json')['files'].items():
        assert digest(repo/name)==expected,name
print('Frozen recovery verified: 394 JVM + 394 Native + 89 desktop; original scene still refused; native clients and full alpha remain unqualified.')
