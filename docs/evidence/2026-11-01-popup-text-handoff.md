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
bundled dylib path. The repaired diagnostic image is built and signed but has not
yet been launched: automatic approval review rejected the
launch as a materially changed local package and requested renewed confirmation.
Both the controlled repaired-window check and the final uninstrumented-window
check remain pending. The defect stays open until those checks pass.

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
