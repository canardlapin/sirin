# Native latency preflight and saved-mask review

Scope: owned worktree `/private/tmp/sirin-workbench-20261031`, parent `efc7ca4`.
Mote: `bd-01M5Z3K47RQTRDXEY7Y2P8TMJ3` (P0.6). No runtime, kernel, build pin or
test threshold changed. The workbench and Native provider qualifications remain open.

## Preserved inputs and one preflight

All 128 files in the October 29 native qualification evidence manifest still
match their hashes. Historical source hashes were checked against commit
`021919e`, rather than today's edited sources. The manifest's `GCRepro.scala`
spelling required correction to Git's `GcRepro.scala`; its content hash matches.
The pinned private nativelib JAR also matches its recorded SHA-256,
`819a43ed28cf90e09822e5695a079b53f7fbb4db7aa9677f1ba4871fc144df35`.

The retained conditional-event binary matches
`87d7a7a94d48f21646053915415e31702517cf984a78b5b4f8c79287d2c2e5d9`.
Before starting the preflight, the protocol fixed one attempt with six samples,
ten-second spacing, at least 80% idle CPU and load per logical CPU at most 0.5.
Only a passing preflight could launch `soak 600 4 1000`, with the original
50 ms event/ticker limit, 128M heap, 15-second GC synchronization timeout and
620-second external timeout.

The unchanged preflight ran from 05:36:01 to 05:36:53 UTC on November 1.
All six samples failed the idle requirement: 73.35%, 74.40%, 73.40%, 73.42%,
74.27% and 73.36%. The load requirement passed in every sample. Exit status was
2, readiness false. The 600-second gate was not run. There was no retry, lowered
threshold or termination of unrelated work. This is an unavailable qualifying
host condition, not a latency pass or a new runtime failure.

## What the source review adds

The review read the hash-verified pinned JAR's Immix synchronizer and mutator
state, both trampoline assembly implementations, and the preserved private
saved-mask patch. It does not describe current upstream main.

The private patch stores the entire saved context mask in the current mutator
and restores it at normal-context trampoline entry before `Synchronizer_yield`.
The existing ARM64 trampoline reads the mutator's saved resume PC after yield
returns. That field is one slot, not a stack of redirected activations.

The earlier diagnostic's active/nested counts cover signal-handler entry and
exit. They do not cover the interval from handler return through trampoline
resume. Consequently, matching handler counts and zero nested handlers do not
prove that another redirect cannot overwrite the outer activation's saved
state. This review has not demonstrated that such a second redirect is
reachable, and does not claim an observed nested failure. The single-slot
resume PC also predates the private mask patch.

Full mask replacement is relevant: it preserves the interrupted context's
other blocked signals only if that saved mask belongs to the current activation.
Apple's archived [sigprocmask manual](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/sigprocmask.2.html)
defines `SIG_SETMASK` as replacement. Its [sigaction manual](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/sigaction.2.html)
describes handler masking and the possibility of other signals during a
handler. Those documents support reviewing the boundary; they do not prove
the cause of the previously observed Darwin mask mismatch.

Before adopting a remedy, the next causal check must cover the full redirected
activation lifetime, preserve unrelated signal-mask bits and verify ownership
of the resume PC. An activation identifier and phase observations must
distinguish handler nesting from a redirect during a still-active trampoline.
Any new instrumentation needs its own frozen controls and must retain its
possible effect on timing. The original long event gate remains separate.
The previous three short candidate passes and one four-worker allocation pass
are retained as bounded evidence, not upgraded by this review.

## Artifacts and verification

Raw artifacts are under `/private/tmp/sirin-native-continuation-20261101`:
`protocol.json`, `historical-integrity.json`, `idle-preflight/`,
`idle-preflight.runner.log`, `gate-status.json`, `source-review.json` and
`regression.log` and `regression-result.json`. `artifacts.json` records their
SHA-256 hashes; its own SHA-256 is
`d155f078b25e40fd537a67d1e2a0d2f5f8551f5b2db30bb3d448152cb7ae6a0c`.

`sbt testJVM testNative` exited 0: 449 JVM and 449 Native tests passed.
The sbt banner reports Eclipse Adoptium Java 21.0.12.1. Desktop/package checks
were not repeated for this documentation-only continuation. No application
executable or production package was changed. The shared checkout's tracked
diff still matches the preserved SHA-256
`1bfa8a28e367ba1c946dcaf2e7febba495c95b8c52127c2379ac590320dfa588`.
