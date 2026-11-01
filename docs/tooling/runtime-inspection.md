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

This is the runtime layer of the inspector. A visible browser, complete per-phase
invalidation causes, render/cache diagnostics and end-to-end UI acceptance remain
open. Use the starter instructions for public application authoring; applications
do not need this development API to implement controls or persistence.
