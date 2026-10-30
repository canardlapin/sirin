#!/usr/bin/env python3
"""Verify frozen control, input-ordering and wrapped-feedback evidence."""
from pathlib import Path
import hashlib,json,re,sys
base=Path(__file__).resolve().parent
repo=base.parents[3]
def read(name):return json.loads((base/name).read_text())
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
for name,expected in read('manifest.json')['files'].items():
    assert digest(base/name)==expected,name
stage1=read('full-checks-1/result.json')
assert len(stage1['results'])==2 and all(x['returncode']==0 for x in stage1['results']) and stage1['source_mismatches']==[]
for name,expected in stage1['source_hashes'].items():
    assert digest(base/'full-checks-1/source'/name)==expected,name
full=read('full-checks-2/result.json')
assert len(full['results'])==2 and all(x['returncode']==0 for x in full['results'])
assert full['source_mismatches']==[]
assert full['source_hashes']==read('full-checks-2/protocol.json')['source_hashes']
for name,expected in full['source_hashes'].items():
    assert digest(base/'full-checks-2/source'/name)==expected,name
counts=[tuple(map(int,x)) for x in re.findall(r'Passed: Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)',(base/'full-checks-2/checks-1.log').read_text())]
assert [x[0] for x in counts]==[69,16,2,6,191,34,23,46]*2
assert all(x[1:3]==(0,0) and x[0]==x[3] for x in counts)
assert 'Passed: Total 87, Failed 0, Errors 0, Passed 87' in (base/'full-checks-2/checks-2.log').read_text()
old=read('fixed-height-control/result.json');new=read('wrapped-height-successor/result.json')
assert old['returncode']==1 and new['returncode']==0
assert old['test_sha256']==new['test_sha256']
assert (base/'full-checks-2/source/desktop/src/test/scala/sirin/desktop/EditorControllerSuite.scala').read_bytes().startswith((base/'wrapped-height-successor/EditorControllerSuite.scala').read_bytes().rstrip())
for mode,result in [('fixed-height-control',old),('wrapped-height-successor',new)]:
    assert digest(base/mode/'Layout.scala')==result['layout_sha256']
    assert digest(base/mode/'EditorControllerSuite.scala')==result['test_sha256']
assert new['layout_sha256']==full['source_hashes']['host/src/main/scala/sirin/host/Layout.scala']
assert digest(base/'fixed-height-control/limit-refusal.png')!=digest(base/'wrapped-height-successor/limit-refusal.png')
assert 'Failed: Total 19, Failed 1, Errors 0, Passed 18' in (base/'fixed-height-control/checks.log').read_text()
assert 'Passed: Total 19, Failed 0, Errors 0, Passed 19' in (base/'wrapped-height-successor/checks.log').read_text()
assert read('targeted-2/result.json')['returncode']==1
assert 'fixture must wrap its field diagnostic' in (base/'targeted-2/checks.log').read_text()
assert read('owner-boundary/targeted-1/result.json')['returncode']==0
assert read('unicode-bound-control/result.json')['returncode']==1
assert 'unpaired surrogate' in (base/'unicode-bound-control/checks.log').read_text()
assert read('unicode-bound-successor/result.json')['returncode']==1
assert 'paragraph exceeds shaping work budget' in (base/'unicode-bound-successor/checks.log').read_text()
assert read('unicode-bound-successor-2/result.json')['returncode']==0
assert 'complex unsupported-glyph feedback retains the explicit paragraph resource refusal' in (base/'full-checks-2/source/desktop/src/test/scala/sirin/desktop/EditorControllerSuite.scala').read_text()
ci=read('workbench-main-ci.json')
assert ci['headSha']==full['base_commit'] and ci['conclusion']=='success'
assert len(ci['jobs'])==9 and all(j['conclusion']=='success' for j in ci['jobs'])
summary=read('summary.json')
assert summary['cases']=={'JVM':387,'Native':387,'desktop':87}
assert summary['source_hashes']==full['source_hashes']
assert not summary['GUI_launched'] and not summary['native_IME_qualified'] and not summary['VoiceOver_qualified'] and not summary['alpha_complete']
if '--sources' in sys.argv:
    for name,expected in full['source_hashes'].items():
        assert digest(repo/name)==expected,name
print('Frozen input/field feedback verified: 387 JVM + 387 Native + 87 desktop; exact wrapped-error control; native clients and full alpha remain unqualified.')
