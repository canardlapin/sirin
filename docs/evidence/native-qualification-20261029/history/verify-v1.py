#!/usr/bin/env python3
"""Replay hashes/outcomes without running a runtime, app, UI, GPU or compiler."""
import hashlib
import json
from pathlib import Path
import re

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
read = lambda p: json.loads((BASE/p).read_text())
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
manifest = read('manifest.json')
files = {str(p.relative_to(BASE)) for p in BASE.rglob('*') if p.is_file() and p.name!='manifest.json'}
assert files == set(manifest['evidence_files'])
for name, expected in manifest['evidence_files'].items(): assert sha(BASE/name)==expected, name
for name, expected in manifest['source_files'].items(): assert sha(ROOT/name)==expected, name
assert manifest['qualification'] is False

available_binaries = {}
def trial(name,seconds,workers,exit):
    r = read(name+'/result.json')
    assert r['command'][1:]==[str(seconds),str(workers),'2048','true'],name
    assert r['returncode']==exit and not r['timed_out'],name
    assert r['timeout_seconds']==seconds+15,name
    assert r['environment']['GC_MAXIMUM_HEAP_SIZE']=='128M'
    assert r['environment']['SCALANATIVE_GC_SYNC_TIMEOUT_MS']=='15000'
    stdout=(BASE/name/'stdout.log').read_text()
    stderr=(BASE/name/'stderr.log').read_text()
    if exit==0:
        assert 'DONE elapsed_ms=' in stdout and 'RESULT live_payload_bytes=' in stdout
    else:
        assert exit==-6 and 'FATAL: Timeout after 15.0s' in stderr and 'DONE elapsed_ms=' not in stdout
    available_binaries[r['command'][0]]=r['binary_sha256']
    return stdout,stderr

assert [r['case'] for r in read('phase-results.json')]==read('phase-protocol.json')['order']
phase_exits=[-6,0,-6,-6,-6]
for r,exit in zip(read('phase-results.json'),phase_exits):
    assert r['runner_exit']==(0 if exit==0 else 134)
    stdout,stderr=trial('phase-controls/'+r['case'],60,1,exit)
    if r['case'] in ['phase-2','phase-3']:
        match=re.search(r'SIRIN_PHASE masks=([^ ]+) counts=([^ ]+) active=(\d+) nested=(\d+) exits=(\d+)',stderr)
        assert match and match[1]=='5,4,5,5,5,5,5,4'
        counts=[int(n) for n in match[2].split(',')]
        assert len(set(counts[:5]))==1 and counts[0] in [1698,913]
        assert match[3:5]==('0','0') and int(match[5])==counts[0]
        attachment=read('phase-controls/'+r['case']+'/sample-attachment.json')
        assert attachment['after_warning'] and attachment['returncode']==0
        assert (BASE/'phase-controls'/r['case']/'native.sample.txt').is_file()
assert [r['case'] for r in read('saved-mask-results.json')]==read('saved-mask-protocol.json')['order']
for r,exit in zip(read('saved-mask-results.json'),[-6,0,0,0,-6]):
    assert r['runner_exit']==(0 if exit==0 else 134)
    trial('saved-mask-controls/'+r['case'],60,1,exit)
stdout,_=trial('saved-mask-survivors-w4-120',120,4,0)
capacity=[int(n) for n in re.findall(r'SAMPLE .*?heap_bytes=(\d+)',stdout)]
assert len(capacity)==24 and set(capacity)=={35790848}
assert 'RESULT live_payload_bytes=4351848' in stdout
assert 'iterations=973246353' in stdout
assert read('builds/phase-build/build-result.json')['returncode']==1
assert 'undeclared function' in (BASE/'builds/phase-build/build.log').read_text()
for name in ['phase-build-v2','saved-mask-build','event-saved-mask-build']:
    assert read('builds/'+name+'/build-result.json')['returncode']==0
control=read('runtime/saved-mask-control.json')
assert set(control['changed_resources'])=={'scala-native/gc/immix/MutatorThread.h','scala-native/gc/shared/SafepointPollTrampoline.c'}
for name in ['phase-diagnostic','phase-diagnostic-v2','saved-mask-control']:
    assert read('runtime-replay.json')['output_sha256'][name]==read('runtime/'+name+'.json')['output_sha256']

aliases=[69,9,2,6,175,30,7,25]
totals=lambda p:[int(n) for n in re.findall(r'Passed: Total (\d+)',(BASE/p).read_text())]
assert totals('integration/checks.log')==aliases+aliases+[50]
assert sum(totals('integration/checks.log'))==696
assert read('integration/result.json')['returncode']==1
assert 'bundle format is ambiguous' in (BASE/'integration/checks.log').read_text()
assert totals('integration-v2/checks.log')==[50]
assert read('integration-v2/result.json')['returncode']==0
assert totals('builds/event-saved-mask-build/build.log')==[2,2,14,13]
rows=read('portability/results.json')
assert [r['returncode'] for r in rows]==[1,0,0]
assert 'flattened framework link: SDL3' in (BASE/'portability/failed-framework-negative.stderr.log').read_text()
original=read('portability/original-package.json');relocated=read('portability/relocated-package.json')
for result in [original,relocated]:
    assert result['native_closure_valid'] and result['codesign_deep_strict_valid']
    assert result['library_loaded_without_UI'] and result['packaged_font_files']==7
    assert [r['path'] for r in result['load_commands'] if r['kind']=='0xd']==['@rpath/libsirin_desktop.dylib']
    assert [r['path'] for r in result['load_commands'] if r['kind']=='0x8000001c']==['@loader_path']
for key in ['library_sha256','sdl_sha256','cfg_sha256']: assert original[key]==relocated[key]
package=read('package-manifest.json')
for folder in [package['app'],package['relocated_app']]:
    p=Path(folder)
    if p.is_dir():
        for name,expected in package['files'].items(): assert sha(p/name)==expected,name

idle=read('idle-preflight/result.json')
assert len(idle['samples'])==6 and not idle['ready'] and all(not s['ready'] for s in idle['samples'])
assert idle['thresholds']=={'min_idle_percent':80,'max_load_per_cpu':0.5}
assert read('pause-gate-status.json')['gate_status']=='not-run'
assert not read('pause-gate-status.json')['qualification']
assert read('pause-gate-status.json')['gate_arguments']==['soak','600','4','1000']
status=read('status.json');ui=read('native-ui/status.json')
assert not status['gc_issue_closed'] and not status['production_runtime_changed']
assert not status['saved_mask_runtime_adopted'] and not status['long_event_qualification']
assert ui['editor_launch']=='rejected_by_automatic_review' and not ui['launch_workaround_used']
assert ui['initial_input_sources']==['Canadian'] and ui['initial_voiceover_enabled'] is True
assert not ui['preference_changes_made']
assert not ui['real_IME_passed'] and not ui['VoiceOver_editor_passed'] and not ui['native_drag_passed']
checked=0
for path,expected in available_binaries.items():
    p=Path(path)
    if p.is_file(): assert sha(p)==expected,path;checked+=1
print(f'Verified {len(files)} frozen files;11 allocation attempts,696 current Scala cases,'
      f'50 affected reruns,31 event compatibility checks,portable positive/negative receipts,'
      f'{checked} available allocation binaries. Long gate and native client validation remain unrun.')
