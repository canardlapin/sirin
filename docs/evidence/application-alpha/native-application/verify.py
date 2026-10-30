#!/usr/bin/env python3
from pathlib import Path
import hashlib,json,re,sys
base=Path(__file__).resolve().parent
repo=base.parents[3]
manifest=json.loads((base/'manifest.json').read_text())
for name,expected in manifest['files'].items():
    assert hashlib.sha256((base/name).read_bytes()).hexdigest()==expected,name
result=json.loads((base/'result.json').read_text())
assert len(result['results'])==2 and all(x['returncode']==0 for x in result['results'])
for name,expected in result['source_hashes'].items():
    assert hashlib.sha256((base/'tested-source'/name).read_bytes()).hexdigest()==expected,name
counts=[tuple(map(int,x)) for x in re.findall(r'Passed: Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)',(base/'checks-1.log').read_text())]
assert [x[0] for x in counts]==[69,12,2,6,190,34,7,31]*2
assert all(x[1:3]==(0,0) and x[0]==x[3] for x in counts)
assert 'Passed: Total 54, Failed 0, Errors 0, Passed 54' in (base/'checks-2.log').read_text()
summary=json.loads((base/'summary.json').read_text())
assert summary['cases']=={'JVM':351,'Native':351,'desktop':54}
assert not summary['GUI_launched'] and not summary['alpha_complete']
portable=json.loads((base/'portable-image.json').read_text())
assert portable['native_closure_valid'] and portable['codesign_deep_strict_valid'] and portable['library_loaded_without_UI']
assert json.loads((base/'history/portable-package-successor/result.json').read_text())['returncode']==0
assert 'Passed: Total 8, Failed 0, Errors 0, Passed 8' in (base/'history/portable-package-successor/checks.log').read_text()
if '--sources' in sys.argv:
    for name,expected in summary['current_source_hashes'].items():
        assert hashlib.sha256((repo/name).read_bytes()).hexdigest()==expected,name
print('Frozen headless receipts verified: 351 JVM + 351 Native + 54 desktop; GUI launch and full alpha remain unqualified.')
