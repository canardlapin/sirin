# GC and editor acceptance tranche — 2026-10-29

The authorized four-part tranche produced an actionable GC reproduction packet,
a bounded conditional-runtime candidate, accepted native editor integration,
and a fresh regression pass. **P0.6 remains open**: no causal fix was accepted,
no production runtime changed, and the original long-event gate did not run.
The tested integration is isolated on `codex/gc-editor-acceptance-20261029` at
`/private/tmp/sirin-tranche-20261029`; the shared checkout's dirty source is
preserved. Editor commits 62d3519 and 9372ca5 are ancestors of this receipt.

## GC results

| Frozen generation | Completed | Safepoint aborts | External timeouts |
| --- | ---: | ---: | ---: |
| Stock 0.5.12 trap,60s / 1 worker | 0/3 | 3/3 | 0/3 |
| Pinned fa4a9e6 trap,60s / 1 worker | 0/3 | 3/3 | 0/3 |
| Stock conditional,60s / 1 worker | 3/3 | 0/3 | 0/3 |
| Pinned conditional,60s / 1 worker | 3/3 | 0/3 | 0/3 |
| Private trap counters | 2/3 | 1/3 | 0/3 |
| Private last-mask counters | 1/3 | 2/3 | 0/3 |
| Apple SIGBUS SA_NODEFER control | 0/3 | 3/3 | 0/3 |
| Original trap controls surrounding SA_NODEFER | 2/2 | 0/2 | 0/2 |
| Pinned conditional,120s / 4 workers | 1/1 | 0/1 | 0/1 |

All allocation trials retain the 128MiB cap, 15s GC synchronization timeout,
2048 survivor slots per worker and 1ms ticker. Independent external deadlines
are 75s or 135s for the 120s check. The primary 12-trial matrix was frozen before
execution and is distinct from both diagnostic generations and causal controls.
No outcome is discarded or relabeled. The shared host prevents a performance
comparison or failure-probability estimate; diagnostic instrumentation also
changes scheduling.

[The unsent upstream packet](upstream-report.md) contains reproduction commands,
source/runtime hashes, native samples, explicit negative controls and next
inspection points. Standalone failures require no SDL/Sirin boundary. Last
mask observations show SIGBUS blocked at admission/yield on both captured mask-
diagnostic failures. These are historical hook observations, not synchronous
mask reads at the stalled PC. The 3/3 failed SA_NODEFER attempts reject that
patch as a remedy. They do not identify the exact cause or exclude every
signal-mask mechanism.

The conditional 120-second check completes 60,005 collections and 1,477,294,490
iterations, validates reachable survivor endpoints, retains 4,350,976 payload
bytes, and has flat sampled heap capacity 35,790,848 bytes after startup.
Capacity is neither live heap nor RSS. This bounded completion does not establish
universal progress, long-term growth bounds or the 50ms UI pause criterion.

All 6 idle-preflight samples fail: 68.51–73.11% idle against the unchanged 80%
minimum, with the unchanged load threshold 0.5 per logical CPU. No unrelated
process was stopped, no preflight was retried until passing, and no 600-second
qualification was launched. The conditional event binary is separately built
from the original probe commit 1fc105e, hash recorded in `status.json`; it is
ready for the original gate in a suitable quiet window. Its 31 compatibility
checks and authoring compilation pass. There is no adoption of conditional
polls as a default in this tranche.

## Native editor and integration

The owner explicitly approved the exact SirinEdit.app launch. An independent
packaged scenario exits0 with `editorScenario=9 presentations=8 recoveries=1
rejected=0`, actual NSTextInputClient focus1/active1 and complete shutdown.
[Independent interactive repetition](editor-repeat/observations.md) remains
inconclusive; its accepted action trace and presentation refusals are retained.
The temporarily enabled existing trace property was removed and the original
configuration hash restored.

[Accepted native editor evidence](../text-editor-20261029/native-acceptance/README.md)
for 9372ca5 establishes OS typing, AccessKit focus/value/selection/click,
Unicode paste, undo/redo, Shift-click selection, native composition controls,
resize, recovery and close. Its source/evidence manifest and 8 available package
artifacts were independently checked. The implemented fixes retry a complete
pending frame after transient present refusal and settle history indicators
after external reset; kernel semantics are unchanged.

The original package verifier passes against its accepted checkout and all 8
available artifact hashes. Running that same strict verifier in the integration
worktree rejects the freshly rebuilt dylib's byte hash; this failure is preserved
and the verifier is unchanged. Both dylibs embed different absolute worktree
install names and SDL rpaths. All 31 named sections match by content or virtual
zero-fill size/type; 74 integrated source/evidence hashes match the accepted
manifest. The rebuilt artifact has its own recorded hash and passed the fresh
integration tests. No new packaged native-UI acceptance is inferred from it.

Fresh checks on this integration worktree pass **323 JVM + 323 Native + 50 desktop
cases**, with examples compilation. The accepted component receipt retains its
unchanged 93 font/Unicode/paragraph/render checks, 789 distinct Scala cases overall,
6 Python receipt checks and native protocol. This tranche's fresh 696 executions
do not claim to rerun the 93 component cases. The earlier integration setup error
ran no tests and is retained separately from the successful successor.

The accepted ordinary-host successor diagnostic remains `qualified=false`
(available host): action p95/p99=24.086/27.015ms and scroll=16.902/17.935ms,
100 measured samples per class. Its initial deadline failure is preserved.
This does not qualify the separate GC 600-second gate. Actual Japanese/Chinese
candidate UI, VoiceOver, true native pointer drag and word-navigation validation
remain open in Mote `bd-01M64NKHTDEJ7WZGQBTA6SMF23`.

## Replay and remaining gate

Run `python3 -B docs/evidence/gc-editor-acceptance-20261029/verify.py` from this
checkout. It checks frozen files/source hashes, all denominators and outcomes,
unchanged preflight limits, integration totals and available local binaries.
The parent editor verifier checks the independently accepted native evidence.
Integrity replay is not runtime qualification.

`replay-runtime.py VARIANT SOURCE_JAR NEW_OUTPUT_JAR` recreates each private JAR
from frozen resources and validates both input and output hashes. First replay
`trap-diagnostic` from the original pinned JAR, then `trap-mask-diagnostic` from
that output; `trap-nodefer-control` separately consumes the original. All three
have already replayed byte for byte. Patches are reviewable and original caches
are untouched. Rebuild native binaries only in fresh directories, using recorded
build commands and link-time environment. Keep every successor's raw output.

Next GC acceptance is the unchanged idle preflight followed by the original
600-second four-worker event/50ms probe. A trap-runtime remedy needs an additional
causal successor with survivor/event confirmation before policy adoption.
The report is prepared locally; posting upstream was not authorized or done.
