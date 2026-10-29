# GC progress and AccessKit native acceptance — 2026-10-29

Owner authorized steps 1–4: isolate the runtime safepoint defect, validate any
supported candidate, qualify native editor behavior, then integrate accepted
changes. Candidate base is editor 62d3519 in a new worktree; preserve all earlier
trial generations and concurrent checkouts. No change to kernel policy or runtime
default is authorized merely by a passing bounded diagnostic.

## Frozen GC protocol

Use the existing Scala Native-only source and known stock/pinned binaries as
controls. Build pinned conditional yieldpoints in a fresh independent sbt build,
then one minimally instrumented pinned trap runtime JAR in another private build.
Original dependency JARs and prior binaries must never be overwritten. Record
source/JAR/binary hashes, patches, commands and all outcomes. Diagnostics use
native lock-free counters; no managed callbacks or allocations in handlers.

The primary matrix has stock trap, stock conditional, pinned trap and pinned
conditional: three serial60s one-worker runs per cell,2048 survivor slots,1ms
ticker,128MiB heap cap, 15s GC sync timeout,75s independent external timeout.
Keep the primary matrix separate from up to three 60s diagnostic trap runs.
All trials are retained; no retry-until-pass or changing acceptance limits.
Stop causal patch work unless diagnostics justify a specific mechanism.

A candidate must preserve survivor integrity and heap bounds, then pass repeated
independent checks and the original 600s four-worker event/50ms gate after the
unchanged idle preflight. A failed preflight is a not-run qualification, not
permission to weaken the gate. Commix/Boehm and universal progress remain outside
this bounded tranche unless necessary to distinguish an observed mechanism.

## Native editor acceptance and integration

Use AccessKit candidate 62d3519, not the older direct Cocoa specimen. Verify native
AX focus/value/selection/button actions, real typing, selection, clipboard/undo,
platform-client composition/cancellation, resize/recovery/close, and ordinary
workbench regression. Japanese/Chinese candidate UI and VoiceOver are separate
qualifications. Preserve original screen/AX observations and package hashes.

Automatic review required explicit confirmation for launching the locally built
SirinEdit.app; the owner supplied that exact approval. The initial rejection and
inconclusive independent UI attempts are retained alongside the packaged pass. Integrate only accepted
changes after required JVM/Native/desktop checks. Preserve the dirty shared root;
if safe landing is impossible, leave a tested reviewable integration commit.

## Recorded causal successor

Signal-mask diagnostics found SIGBUS blocked at both yielding and managed
admission on stalled mutators. Before execution, freeze a native-only causal
control adding SA_NODEFER solely to the Apple SIGBUS handler installation.
The order is one original trap baseline,three 60s candidate attempts,then one
original trap baseline; all128MiB/15s/75s limits stay unchanged. This control
tests signal deferral as a mechanism and is not a production runtime choice.
Preserve every outcome and require independent survivor/heap and long-event
validation before any stronger claim.

## Final bounded outcome

Accepted editor successor 9372ca5 is fast-forwarded into the isolated integration
branch. Fresh 323 JVM, 323 Native and 50 desktop checks and examples compilation pass.
The 12-trial primary GC matrix reproduces all 6 trap stalls and completes all 6
conditional controls. Private diagnostics expose last-observed blocked SIGBUS;
the frozen SA_NODEFER control fails 3/3 while both original controls complete.
No causal fix or default change is accepted. Four-worker conditional survivor
validation passes 120 seconds. The unchanged idle preflight fails all 6 samples,
so the original 600-second event/50ms gate remains not-run and P0.6 stays open.
See `docs/evidence/gc-editor-acceptance-20261029/` for all generations, exact runtime
replay, independent editor repetition and the unsent upstream report. Native input
methods, VoiceOver and true drag remain an explicit successor qualification.
