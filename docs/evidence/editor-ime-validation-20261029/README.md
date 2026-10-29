# Preserved editor Cocoa IME validation — 2026-10-29

This is an independent check of the existing `SirinEditor` package in
`/private/tmp/sirin-gpu-host-20261028`. No editor source was changed. The active
AccessKit integration at `/private/tmp/sirin-editor-20261029` was left untouched.

The bounded packaged command `SirinEditor editor scenario` exited **0** in
1.934 s. It reached `editorScenario=8 presentations=7 recoveries=1 rejected=0`.
Both native composition calls report `focus=1 active=1`,
`client=SDL3TranslatorResponder keyWindow=1`. The scenario exercises provisional
Japanese marked text without writing the bound Cell, commit, one-step undo,
clipboard copy/cut/paste and undo/redo, resize, renderer recovery and native
ownership cleanup. The raw stdout/stderr and external-runner receipt are in
`scenario/`. The scenario completed before subsequent CUA app inspection;
no manual raising or other CUA intervention is attributed to its successful run.

Separately, actual CUA keyboard/pointer checks showed title replacement,
Cmd-Z/Shift-Cmd-Z, multilingual multiline paste and single-step undo working.
The initial accessibility field click did not focus the control. Raising the
window and clicking its visible title field enabled real input. The reported
focused accessibility element remained the window even while fields changed.
These observations are transcribed in `manifest.json`; the original document
and title were restored, and the test window was closed.

## Scope and preserved failures

This validates the tested preserved package's Cocoa NSTextInputClient marked
text/commit path and ordinary native keyboard/paste actions. It does **not**
validate an actual Japanese IME candidate UI, every OS input method, a screen
reader, or the replacement AccessKit bridge. The direct Cocoa accessibility
implementation conflicts with the planned AccessKit transport and is superseded
by the active integration; these results must not qualify that successor.

The earlier `nativeIME focus=0 active=1` transport refusal and packaged stage-2
deadline failure remain in `history/`. A new passing run does not rewrite them.
No code fix or universal focus-race resolution is claimed. Package launcher,
JAR, native library, configuration and relevant source hashes are recorded.

Re-run using the preserved package, a new output directory and the existing
external watchdog:

```sh
python3 -B probes/gc-repro/run.py --out target/editor-ime-NEW --timeout 140 -- \
  /private/tmp/sirin-gpu-host-20261028/desktop/target/package-editor/SirinEditor.app/Contents/MacOS/SirinEditor editor scenario
```

No test suite was rerun for this source-unchanged runtime check; the new evidence
is the actual packaged scenario and CUA observations, not the older 41-test run.
