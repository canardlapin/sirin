# Text transport while popup focus is restored

The prior packaged workbench lost the beginning of text typed immediately after
Escape dismissed its dirty-quit modal. The original natural-speed observations
remain in the semantic-part-focus evidence; this investigation preserves them
rather than treating later passing attempts as disproof.

## Diagnosis and repair

A diagnostic package logged event kinds, key codes, readiness, text-client state
and presentation boundaries without logging document text. Two natural-speed
trials happened to pass, with text input reactivated just before the first key.
A diagnostic-only 200 ms render-worker delay made the gap repeatable. Starting
from Title `Delayed `, Escape followed immediately by `abcdefghi` left the title
unchanged. All nine printable key-down events reached the owner while text input
was disabled and focus still named the old popup button. No native text events
were produced. The next presentation reactivated text input too late.

The controller now prepares native text transport for a surviving pending restore
field, using its last accepted geometry and the same focus restoration policy.
Logical focus, reference signals and accessibility focus still wait for accepted
presentation. Text, composition and transition key-down events wait in the
existing bounded owner queue. No key code is synthesized into text. Programmatic
popup closure also refreshes the native text client without needing another
input event. The policy and rejected alternatives are recorded in
[the design decision](../design/decisions/2026-11-01-popup-text-handoff.md).

## Verification

A regression failed before the repair because immediate typing did not require
accepted geometry. It now checks that a text event stays queued while the restore
field has no logical focus, then reaches that field intact after presentation.
Additional checks cover explicit and non-text restoration, Nowhere, vetoed
dismissal and retirement. Existing semantic-focus and recovery checks pass.
No assertions or thresholds were relaxed. Diagnostic tracing and worker delay
are removed from production source and retained as a separate artifact patch.

The full run passed 454 JVM, 454 Native and 151 desktop tests. Examples compile
and the uninstrumented production package builds. Its native dependency closure,
fonts, deep/strict signature and library-only loading pass. A Scala Native probe
reaches all nine ABI entries in that exact package; the loader log confirms the
bundled dylib path. The repaired diagnostic image was subsequently launched with the owner
confirmation received on November 1. Two controlled real-window trials now pass,
as recorded below. The final uninstrumented-window check remains pending; the
defect stays open until that check passes.

## Evidence boundaries

Raw logs, diagnostic patches, the original failed app, the repaired diagnostic
package receipt and source snapshots are retained under
`/private/tmp/sirin-popup-input-20261101`. The isolated fixture is under
`/private/tmp/sirin-workbench-acceptance-20261031/documents`; all diagnostic drafts
were discarded. Actual IME, VoiceOver, physical keyboard/trackpad and broad
application acceptance remain separate gates. Shared Native tests and the ABI
probe do not establish a Scala Native desktop application provider.

The 33-file log/source/receipt manifest has SHA-256
`00186cdf3ee14f1d3daebb9588a114cc2bfe8ebb51962a70ea3fa4c71bee06b0`.
Preserved app bundles are excluded from that manifest; their jar, native-library
and configuration receipts identify the tested variants.

Tracking issue: `bd-01M6AZSB5Q1G40KYAS1EQY1BYB`.

## Approved controlled window check, November 1

The approved isolated package at
`/private/tmp/sirin-workbench-acceptance-20261031/SirinWorkbench.app` contains
repair `3561504`, event tracing and the diagnostic-only 200 ms render delay.
Its jar SHA-256 remains
`e471951ccc7926f2dcc54ba936a013b8ad515d7ab81ed5f2b9299ad42a9fab9a`.
This is the repaired diagnostic package, not the newer inspector package.

After opening the saved Acceptance Alpha fixture and keyboard-focusing Title,
the synthetic draft title was set to `Repaired `. Command-Q presented the dirty
quit dialog with Keep editing focused. Escape and `typeText("abcdefghi")` ran in
one computer-use call with no intervening observation. After the queued input
drained, both the title field and window title read `Repaired abcdefghi`.
A second trial appended `JKLMNOPQR` with the same Escape/immediate-typing
sequence and produced `Repaired abcdefghiJKLMNOPQR`. The final window was also
visually inspected. Both trials retained every character. Intermediate snapshots
showed partially delivered text while deliberately delayed frames were pending;
these are not final text-loss results.

Each trial records nine native text events before the first restored-field
presentation and nine subsequent text-event dispatches, with native transport
enabled before the first text event. Delivery reaches the field after accepted
frames. Its `text=false`
field describes logical focus; the separate `text-input mode=1` record confirms
native transport was enabled. This supports the repair's transport/queued-delivery
mechanism without establishing production latency or arbitrary input timing.

Between trials, Shift-Tab changed the internal focus target from 352 to 359,
but the native accessibility snapshot omitted a focused element. Tab returned to
352 and the accessibility tree reported Keep editing. Preserve this discrepancy;
it does not establish complete native keyboard-focus or VoiceOver acceptance.

Discard and quit removed only the synthetic unsaved draft. The app disappeared
from the running-app inventory. The saved fixture SHA-256 remained
`1e717858dda45fc34d230f0c58294cd96d9a286e0ed0a8776bdd51fa86a37d28`.
The trace and package/fixture receipt are retained in
`/private/tmp/sirin-popup-window-20261101`. The original failed trials and their
manifest remain unchanged. An uninstrumented production-window repeat, actual
IME, VoiceOver and physical input remain open.

Required regression commands `sbt testJVM` and `sbt testNative` passed all
470 tests on each platform at source HEAD `dbe6da3`. The initial sbt launch
was denied access to its boot lock; the successful retry used the existing cache
with approved access. Both logs are retained. No implementation changed in this
window-check continuation. The six-file artifact manifest has SHA-256
`d5182c600206cd3c974213484edf5eb95a94e9dc287288e2afd997bacd49d9cd`.

## Production-window follow-up, November 1

The approved uninstrumented package at `1a9baa3` now passes three immediate
Escape-to-typing trials: body input in Light and Dark, and the original
Title `Probe ` followed by `abcdefghi`. Every character survives; the latter
produces `Probe abcdefghi` in both field and window title. Exact saving/relaunch
also passes. The required suites pass 476 JVM and 476 Native tests unchanged.
This verifies the specific production missing-prefix defect without extending
the claim to IME, VoiceOver, physical input or arbitrary timing. See
[the package receipts and window evidence](2026-11-01-production-window-acceptance.md).
