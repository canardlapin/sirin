# Inspect committed runtime state

`HeadlessRuntime.inspect()` is a development API available on JVM and Scala
Native. Call it on the runtime owner thread between transactions, outside signal
evaluation, observers and release callbacks. It returns copied diagnostic data;
it does not read or retain document objects and does not drive pending work.

```scala
import sirin.engine.*

val snapshot = runtime.inspect()
println(snapshot.text)
```

Default limits return at most 128 scopes, 512 signals, 1,024 dependency edges,
128 commands, 128 work tickets and 128 recent trace/failure records. Text fields
are capped at 128 UTF-16 code units without splitting a surrogate pair. Each
section reports its total and omitted rows. Dependency totals describe only the
selected signals; referenced input IDs can belong to omitted rows. Limits can be
raised to 8,192 rows per section and 1,024 code units per string, or set to zero.
A snapshot's size does not grow with the full application graph.

Names, command refusal reasons and exception class names are included. Cell and
signal values are hidden by default. To examine a controlled test fixture:

```scala
val snapshot = runtime.inspect(
  InspectionLimits(nodes = 8192, edges = 8192),
  includeValues = true
)
```

Previews support strings, primitive numbers, booleans and characters. Arbitrary
objects, collections and functions stay opaque; their `toString` is never called.
The snapshot includes no exception objects, callbacks, scopes, signals or command
instances. Retaining it after a document closes cannot retain that document.

To diagnose a refused save, find `document.save` in `commands.rows`. Inspect its
`refusal`, `running` and `enabled` fields. Follow `enabledNode` through `edges.rows`
(`node <- input`) to the committed inputs. An absent enabled node/value means the
signal was not materialized in the committed graph, not that it evaluated false.
The workbench regression uses this path to connect `command disabled` to the
empty Title input. Command IDs are scoped: use the owner ID to distinguish commands
with the same name in different documents.

A signal's `changedBy` is the last retained cause that committed a value change.
Find it in `traces.rows`, then follow parents while present. Equality-suppressed
writes and failed/refused transactions do not become committed change causes.
A missing record can have aged out of the trace ring. Diagnostic signal IDs are
stable only while a node remains materialized; they are separate from cell and
scope IDs and never determine component identity.

Work rows show owner-side scheduling flags. `started` means selected/submitted
for execution, not necessarily currently using a CPU. `completed` means the owner
has collected a result. A worker may have finished while that flag remains false;
`workers.completed` counts results still waiting in the worker transport. Inspection
does not collect those results, submit more work or run callbacks.

Capture time and storage are bounded by the output budgets plus the runtime's
existing driver-counter scans. Runtime bookkeeping adds fixed numeric metadata to
live nodes, one registration per live command, and bounded failure strings beside
the existing trace ring. Explicit snapshots are not collected every frame. Timing
from the synthetic workbench test is a local observation, not a renderer latency
or complete instrumentation-overhead qualification.

## Visible development window

The JVM development host supplies an optional **Developer → Inspector** menu
(Command-Option-I on macOS). Set `sirin.inspector=true` in the launched JVM. For a
local development run, append it to the forked process options:

```sh
sbt --java-home <jdk25-home> \
  'set desktopJVM / Compile / run / javaOptions += "-Dsirin.inspector=true"' \
  'desktopJVM/runMain sirin.desktop.PersistentWorkbench --documents <test-directory>'
```

For an app image, add `java-options=-Dsirin.inspector=true` in the `[JavaOptions]`
section of `Contents/app/SirinWorkbench.cfg`, then re-sign the development image.
The default package leaves the inspector disabled. Enabling it adds a tool window
and application menu; it does not change the application's quit decision.

Choose **Refresh** to capture a new snapshot. Captures are explicit and coalesced;
there is no idle polling. Search matches captured labels and details. Five rows
appear per page, and the detail area scrolls below fixed navigation controls.
Select a record and follow its signal, scope or cause links. Malformed Unicode and
control characters appear as literal escapes, while snapshot data stays unchanged. If a linked record is
outside the sample, the window says so. **Capture more** doubles the scope, signal
and command budgets up to 8,192 rows; dependency edges remain capped at 8,192.
Closing the inspector clears its snapshot and cancels any queued capture.

**Frames** shows each window's requested, rendered and last presented generations,
input backlog, presentation count and copied render-failure history. Frame metrics
include queue/worker time, dimensions, program/readback bytes, path and cache
summary at render time. The render worker supplies those summaries only when
inspector mode is enabled. They are not live reads of a worker-owned cache, and
worker time is not end-to-end input latency. Explicit test captures can add a
readback outside the production path being reported.

The inspector uses public controls and ordinary keyed window ownership. Its model
and navigation tests run on JVM and Native, and its desktop views have offscreen
CPU and retained-GPU rendering checks. Actual packaged-window acceptance remains
pending. Full per-phase invalidation causality and latency qualification also
remain open. Applications do not need this development API to implement controls
or persistence.
