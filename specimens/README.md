# Async workbench authoring specimen

`workbench` is executable on JVM and Native. Its application model imports only
public `sirin.*` contracts; the separate driver supplies `HeadlessRuntime` and a
fixed two-thread pool. It exercises latest search, revision-associated save,
keyed row editing and a capacity-two lossless import. There is no renderer yet.

```sh
sbt workbenchJVM/run workbenchNative/run
sbt testJVM testNative
```

Both runs print:

```text
search=Result: sirin
imported=6 rows=8
dirty=false channel=Drained
```

`WorkbenchSuite` additionally checks retained drafts and validation feedback across
refresh/reorder, retirement on row removal, saving an older revision while newer
edits remain dirty, and cancellation waking a blocked import without delivering
buffered records. A late uncooperative obsolete search cannot overwrite the
current query. A save snapshots a **committed** cell: commit text edits before
dispatching save. Dispatch refusal is ordinary application feedback and should
not silently be ignored or converted into outer-transaction refusal.

The importer is a single-operation child scope. Graceful completion closes its
channel, then drains accepted records; create another session for another import.
`ImportSession.cancel` requests work cancellation and explicitly abandons delivery.
Completion and progress have separate bounded transport queues: completion alone
never establishes that the channel has drained.

The driver pumps on its UI owner, parks with `awaitActivity`, and uses a bounded
shutdown await after nonblocking host close. An uncooperative Work may outlive that
await; cancellation cannot undo external effects. Scala-created workers may call
Scala producer endpoints. A foreign C producer requires a separate pointer-only
shim integration.

## Preliminary ScalaFX comparison

`scalafx-workbench` is a compiled application-controller counterpart. It uses
ScalaFX properties and subscriptions, JavaFX `Task`/`AnimationTimer`, bounded
executors, a FIFO, retained row objects and explicit close. It does not implement
a replacement framework or an artificial transaction layer. Canceled queued
searches are removed so a stable latest query can progress when physical workers
become free. Search results/failures and imported records are guarded against
obsolete generations. Both specimens omit interactive views.

The pin is `org.scalafx:scalafx_3:26.0.0-R38`, matching the
[official quick-start guide](https://scalafx.org/docs/quickstart/), observed
2026-10-27. This is a reproducible comparison pin, not a claim about the latest
release. The [property guide](https://scalafx.org/docs/properties/) documents its
binding and subscription style. Compile explicitly with a suitable JDK:

```sh
sbt --java-home /path/to/jdk-25 scalafxWorkbench/compile
```

| Concern | Sirin specimen | ScalaFX specimen |
|---|---|---|
| Latest search | `scope.latest(query)` | Task cancellation, queue removal and generation guard |
| Bounded complete stream | Scoped `ProgressChannel`, capacity, owner pump | Capacity-two FIFO, worker put, pulse drain, cancellation and epoch guard |
| Save association | Committed `Versioned` snapshot and serial command | Captured text/revision and single-worker bounded executor |
| Draft retention | Keyed component scope and live Inputs | Retained row objects keyed by ID during refresh |
| Validation | Refused command plus explicit editor feedback | Controller validation before mutation |
| Ownership | Scope disposal retires channels/jobs/editors | Explicit subscription/task/timer/executor cleanup |

The candidate advantage is less application code for async ownership, publication
and identity rules, with those rules exercised by a common kernel. The ScalaFX
controller also expresses the core workflow directly and has useful property
bindings. Source size alone would conflate different contracts and is not scored.

This is **preliminary authoring evidence**. ScalaFX is compile-checked only here;
its interactive behavior and saturation/cancellation scenarios still need runtime
qualification. Neither specimen qualifies visual quality, accessibility, focus,
keyboard use, text editing, startup, packaging or the clear usability/elegance
superiority required by VISION §1.1. A matched interactive JVM workbench and
competent ScalaFX UI remain the acceptance experiment once the Sirin host exists.
