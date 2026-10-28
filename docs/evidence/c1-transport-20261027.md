# Bounded progress, real workers and workbench, 2026-10-27

Base checkpoint: `a821946`. This receipt covers the authorized C1.6a extension,
public-API workbench and preliminary ScalaFX authoring comparison. Concurrent
graphics/text/Unicode edits are preserved and excluded from this checkpoint.

## Implementation and review

The optional fixed Scala-created worker pool shares the controlled scheduler's
command/latest semantics. Synchronized bounded job/result transport separates
physical work from logical tickets. Disposing an owner retires queued requests,
requests cancellation of running work and suppresses detached late results.
Executing serial work retains its slot until actual termination/delivery. Pool
shutdown is nonblocking; bounded physical retirement can be awaited after host
close. Workers capture transport and Work, never terminal UI callbacks.

Progress channels implement finite capacity, Ordered/CompleteStream FIFO,
CoalesceLatest, per-entry UI reservations, nested rollback, graceful close,
explicit abandonment and scope disposal. Independent worker offers survive a
refused UI attempt. Publication freezes queues through short monitor operations,
then evaluates user code without transport locks. Receiver leasing is atomic;
refusal/defect restores the head and wakes blocked coalescing producers. A drain
stops/reports the failure once. Reservation identity contains no transaction/host
reference and commit clears reservation/replacement metadata.

Runtime cancellation registration avoids the flag/wait lost-wakeup race. Custom
Cancellation implementations use a bounded 25 ms off-owner recheck fallback.
The UI owner never waits inside Progress.emit; it reports Full. Pump budgets
progress and completions fairly, and awaitActivity uses a sequence-before-ready
check to prevent lost notifications. Completion and channel drainage are separate.

The independent reviewer ran no edits/builds. Atomic leasing, sticky worker-local
wrong-thread errors, detached reservation metadata, rollback wakeups and editor
validation findings were fixed and re-reviewed. Final source verdict found no
remaining blocker, conditional on the main session's final checks. See
[review decision](../design/decisions/2026-10-27-progress-workers.md).

## Independent and threaded checks

`ProgressModel` has no engine/Signal dependency. Immutable slot histories describe
committed producer offers plus tentative UI alternatives, rather than copying
engine undo closures/entry mutation. Each platform checks **150 programs × 40
steps × 3 policies = 18,000 progress steps**, with capacities 1–4. The first half
of each program keeps channels live to exercise pressure/rollback repeatedly;
the second half adds graceful close and cancel. Programs compare offered receipts,
root outcomes, nested refusal and caught-defect behavior, queue size, public state,
ordered receiver writes, failure receipts and accepted/delivered/coalesced/abandoned
counts. The conservation law is checked after every step. Across JVM/Native:
**36,000 progress steps**, in addition to existing reference-model coverage.

Twenty engine tests cover deterministic/threaded edges beyond those sequential
interleavings: concurrent offers and rollback, nested coalescing, close/cancel,
receiver retry, leased-tail barriers, publication without callback lock retention,
fair drain, UI-owner nonblocking emit, capability guards, runtime/custom cancellation,
host close, physical/logical retirement, serial shutdown, terminal/progress ordering,
atomic head selection, detached metadata and rollback wakeup. The wakeup regression
orders the producer's capacity check under the queue monitor before receiver
rollback, so it does not depend on a sleep or scheduling luck.

## Application specimen and authoring comparison

`specimens/workbench` uses public contracts for async search, serial snapshot save,
keyed rows/drafts and a bounded streaming import. A separate real-worker headless
driver runs on JVM and Native. Five application tests check the trace, invalid
editing feedback/draft retention across refresh/reorder/removal, saving an earlier
revision during later edits, blocked import cancellation and an uncooperative
obsolete search completing after its replacement. The initial trial incorrectly
saved within the same edit transaction and ignored ordinary dispatch refusal;
the corrected driver commits edits before capturing the committed snapshot and
requires successful dispatch. Kernel snapshot semantics were preserved.

The ScalaFX controller uses pinned 26.0.0-R38, ScalaFX bindings/subscriptions,
JavaFX Task/AnimationTimer, bounded executors, a capacity-two FIFO, retained row
models and explicit cleanup. Canceled queued searches are removed; generation
checks guard obsolete results/failures/import records. It validates duplicate
IDs before refresh. The pin follows the
[official quick-start guide](https://scalafx.org/docs/quickstart/), observed
2026-10-27; it is not a latest-release assertion. See the
[comparison and run instructions](../../specimens/README.md).

ScalaFX is compile-checked here, not runtime-qualified. Source review and compilation
support a preliminary authoring comparison. They do not establish interactive
behavioral equivalence or Sirin's required clear superiority in usability/elegance.
Neither specimen includes graphical controls or a desktop host.

## Verification

`sbt testJVM testNative examples/compile probeWindow/test probeThreads/test
workbenchJVM/run workbenchNative/run` exited **0** on macOS arm64, Java 21.0.12.1,
Scala 3.7.4, sbt 1.12.14 and normal Scala Native **0.5.12**. The final
uncooperative-search control was added after the JVM workbench portion; the
supplemental `sbt workbenchJVM/test` also exited **0** with all five final tests.
Native compiled and passed those five in the full run. Existing suites needed no
repeat because no engine, model or other application source changed.

| Final qualification set | JVM | Native |
|---|---:|---:|
| Core | 7 | 7 |
| Transaction / topology / reconciliation / commands | 103 | 103 |
| Progress and real workers | 20 | 20 |
| Existing reference-model suites | 25 | 25 |
| Progress oracle | 3 | 3 |
| Workbench | 5 | 5 |
| ABI / mailbox | — | 27 |

**353 distinct platform-specific checks qualified, zero failures or ignores.**
The full command executed 352 owned checks plus 82 observed concurrent graphics
checks (41/platform); the supplemental JVM workbench run executed five. Those
concurrent graphics checks do not qualify its separate text/Unicode/paragraph
components, and their source/build edits are not included here. No build-reload
warning occurred. The captured build/source hashes identify the observed working
configuration; they are not an assertion that uncommitted graphics is in this
checkpoint. All listed source hashes were verified unchanged after the run.

The final ScalaFX controller compiled with explicit `--java-home` selecting
Java **25.0.4.1**, under the same Scala/sbt versions and strict warning settings.
It was not launched. Both Sirin real-worker traces printed:

```text
search=Result: sirin
imported=6 rows=8
dirty=false channel=Drained
```

Raw logs, the meaningful initial application snapshot/refusal failure, and SHA-256
source/log manifests are in `c1-transport-20261027/`. Raw warning whitespace is
preserved. The initial failure is not counted as final qualification; it records
the corrected caller behavior described above.

## Remaining gates

- Event delivery, deadline/debounce support and full settle diagnostics/frame driver.
- Full Stage 1 semantic and authoring review, explicit reparent/outlets and bounded
  virtual realization.
- Interactive host, matched ScalaFX UI and actual usability/elegance qualification,
  accessibility/focus/input/packaging and visual-quality acceptance.
- Foreign C producers: pointer-only shim integration; never enter Scala endpoints
  on foreign-created threads.
- P0.6 GC/pause/stall/Commix qualification remains unchanged. No soak or idle-host
  preflight was run during this tranche. Default Native remains 0.5.12.
