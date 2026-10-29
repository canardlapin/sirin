# Independent approved-app repetition

The owner explicitly approved launching the exact SirinEdit.app at
`/private/tmp/sirin-editor-20261029/desktop/target/package-editor/SirinEdit.app`.
The earlier automatic-review rejection is preserved in the session history;
no alternate launch route was used before the owner's approval.

CUA initially took approximately 4637 seconds to return from `getApp`.
The app was already running when the delayed result arrived. A later Dock
lookup also timed out. These are automation delays, not editor timing results.

Independent CUA AX set-value and typing attempts did not yield refreshed visible
values. A fresh launch exposed the complete AccessKit tree, but repeated
coordinate/semantic interactions still did not establish interactive acceptance.
The optional existing bounded trace shows accepted native action requests and
subsequent presentation refusals in this repetition. These attempts are retained
as inconclusive, not replaced with the separate integration session's successes.
There are no saved screenshot artifacts from this repetition.

Only the existing opt-in `sirin.editor.trace` launch property was enabled.
`original-SirinEdit.cfg`, configuration hashes and `editor.trace` are retained.
The app was closed and the original configuration restored; the restoration hash
matches the original and accepted package manifest. No source change was made.

After explicit approval, the unchanged packaged `editor scenario` control ran
independently and exited0 in1.794 seconds: editorScenario9, presentations8,
recoveries1, rejected0. It reported actual NSTextInputClient focus1/active1,
SDL3TranslatorResponder and keyWindow1, completed its assertions and released
all tracked native owners. Raw stdout/stderr/status are in `../editor-scenario`.

The integrated source and accepted native observations are in
`../../text-editor-20261029/native-acceptance/`. The accepted checkout
passes its strict manifest verifier, including8 available package/static-library
artifact hashes. Integrated source/evidence hashes match separately; the fresh
worktree dylib has its own hash and does not match the historical package bytes. Those
receipts establish the bounded interactive acceptance; this repetition adds only
the independent packaged scenario pass and the explicit inconclusive UI record.
Japanese/Chinese input-method candidate UI, VoiceOver and true pointer drag remain
open in Mote bd-01M64NKHTDEJ7WZGQBTA6SMF23.
