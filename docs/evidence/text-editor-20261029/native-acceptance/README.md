# Approved native continuation — 2026-10-29

This succeeds the isolated implementation checkpoint62d3519 after the owner
approved launching the specific local SirinEdit app. The original receipt and
failures are preserved at that commit and in the parent manifest. No source in
the shared checkout or the older direct Cocoa editor worktree was replaced.

## Native behavior and corrections

[Computer-use observations](ui-observations.md) record actual macOS typing,
keyboard undo/redo, Unicode clipboard paste, native pointer focus/Shift-click
selection and AccessKit focus, set-value, text-selection and button actions.
The inspected AX values and rendered fields agree. The [native action trace](interactive-actions.trace)
records accepted request kinds1/2/3/4 and matching presentations; no managed
upcall is involved. Stale snapshots were distinguished from completed frames.
The background-launch path needed explicit native window activation before
Metal supplied visible drawables; it is not an automatic-background-render claim.

Two host issues were found and corrected without changing kernel semantics:

- A transient surface presentation refusal left the loop waiting indefinitely
  for another OS event. A complete pending frame now retries on a bounded16ms
  wait; both editor and ordinary host request normal foreground activation.
  The ordinary host retries only when it has a current complete frame to present.
- History indicators were published before controller synchronization after an
  external replacement. They now settle after capture, and AX focus updates the
  last editor used by Undo/Redo. The extra indicator settle is bounded16 driver
  steps and occurs only when the indicator values change.

The specimen has an optional `sirin.editor.trace` system property for diagnosis.
It records at most512 owner-side event/readiness/presentation facts, deduplicates
unchanged presentation attempts and records no field/clipboard text. It is off
in the final packaged app. The initial diagnostic builds, traces and samples
remain under [history](history/); they are not relabeled as final packages.

The [final packaged editor scenario](final-editor-scenario.log) exits0 with
`editorScenario=9 presentations=8 recoveries=1 rejected=0`. It uses the real
Cocoa NSTextInputClient: native logs report `focus=1 active=1`,
`SDL3TranslatorResponder` and `keyWindow=1` for mark and commit. It proves preedit
stays outside the Cell, commit is one undo step, clipboard copy/cut/paste and
undo/redo, resize, actual device destruction/recovery, external-reset history
indicators, native close and zero tracked native owners/frames/tiles/health after
shutdown. Its clipboard backup/restore uses SDL plain text. This direct control
is not a Japanese/Chinese input-method or VoiceOver user session.

## Regression evidence

The [final matrix](final-matrix.log) passes the required complete JVM323 and
Native323 cases, followed by all50 desktop cases. The unchanged font/Unicode/
paragraph/render93 cases remain qualified by the parent component matrix,
for789 distinct Scala cases overall. The final [native protocol](final-protocol.log)
and [six Python receipt tests](final-python.log) pass. No test, renderer budget,
precision rule or frozen performance threshold changed.

The first ordinary-host diagnostic hit its fixed120-second deadline before any
accepted presentation (`benchmark=0`). Its [failed invocation and empty raw receipt](history/host-regression-1/)
are retained. After the host-loop correction a separately hashed package ran
once, with no concurrent candidate compiler. [The successor invocation](host-regression/invocation.json)
exits0 and independently validates the frozen600x360 logical/1200x720 physical
fixture,100 records,40 warmups and200 measured samples (100 per class).
Input-to-present still begins at the injected native SDL timestamp and ends at
the matching successful presentation. [Raw samples and phases](host-regression/benchmark/)
and [recomputed summary](host-summary.json) preserve the denominators.

| Class | p50 ms | p95 ms | p99 ms | Maximum ms | Frozen p95 / p99 limit |
| --- | ---: | ---: | ---: | ---: | ---: |
| Action | 16.416 | 24.086 | 27.015 | 41.549 | 50 / 100 ms |
| Scroll | 16.551 | 16.902 | 17.935 | 26.771 | 100 / 200 ms |

This is an **available-host diagnostic**, not a formal quiet-host qualification:
`qualified=false`. Preflight idle samples65.88/64.50/47.45% and load5.39/5.20/5.03
are retained. The owner's quiet-precondition waiver persists; no process was
stopped to improve the result and no quiet-host retry was attempted. The failed
initial diagnostic is not an accepted sample or a causal comparison.

## Remaining qualification

Real Japanese/Chinese input methods, VoiceOver and true native pointer drag are
tracked in Mote `bd-01M64NKHTDEJ7WZGQBTA6SMF23`. The CUA drag attempt delivered no
motion and is not counted as a pass; native Shift-click selection passed. The
AccessKit character-length fallback and unqualified screen-reader word navigation
from the parent receipt remain explicit. No Native shipping app, other platform,
full accessibility parity, arbitrary uncached zoom or browser parity is claimed.
The complete asynchronous search/save/reorder/import workbench remains a later
product integration gate.

`manifest.json` freezes current sources, final package/config/static-library
hashes and this continuation's evidence. The parent verifier checks the original
62d3519 blobs against their original manifest before checking this successor and
replaying the unchanged latency validator; it launches no native UI or GPU.
