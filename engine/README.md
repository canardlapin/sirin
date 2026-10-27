# Headless core

The JVM/Native `HeadlessRuntime` implements transactions, static and dynamic
signals, scoped ownership, observations and queued effects. Widget hosting,
component reconciliation, tasks, commands, channels and event streams are later
stages. Calling those unsupported `Scope` methods raises `KernelError.Deferred`;
there are no successful placeholder implementations.

```scala
import sirin.*
import sirin.engine.*

val host = new HeadlessRuntime()
val workspace = host.scope("workspace")
val left = workspace.cell(7)
val right = workspace.cell(7)
val selectRight = workspace.cell(false)
val selected = selectRight.flatMap(if _ then right else left)
val panel = workspace.child("panel")
panel.effect(selected)(value => println(value))
host.flushEffects() // initial value, in its own transaction
host.transact { selectRight.set(true); right.set(9) }
host.flushEffects() // 9
host.transact { panel.dispose() } // removes its observation and queued effects
host.close()
```

`flatMap` uses a stable `Equality` for its result. A selector may allocate fresh
signal descriptions. It runs when its input changes, while selected-branch data
changes flow through existing edges. Graph edits apply between private passes;
only a settled graph publishes. A dependency cycle or pass-budget failure
rejects the whole candidate. The budget is configurable at host construction.

Scope child names are diagnostic. `scope.mount(position, definition)` exercises
static owner/position/definition identity. Replacing the definition resets that
position's scope; placing the same definition twice is an error. This is the
headless lifecycle boundary, not component rendering or keyed reconciliation.

Scope operations join an active transaction. Refused attempts undo mounts,
observations, staged writes and disposal. `scope.callback(action)` validates
lifetime when delivered. `scope.afterCommit` also checks ownership after the
commit. Disposal of a parent invalidates all descendants and removes queued
effects. A provisional cell cannot provide a committed snapshot until mounted.

`scope.onDispose` transfers an acquired lease. Committed disposal releases it;
rollback releases newly transferred leases as compensation. Callbacks execute
once under a read-only guard, and one failure does not stop other releases.
This mechanism does not implement deferred native/GPU retirement.

Explicit `host.read` pins a root until close; `scope.read` pins it until disposal.
Observations root their dependencies until cancellation/disposal. Detached inner
derivations disappear when no live root needs them. `lifetimes` reports engine
node/edge/owner/cell/watch/job counts, not process memory. The implementation still
traverses/copies graph metadata each transaction; performance is unqualified.

See `docs/design/VISION.md` for semantics and `docs/evidence/` for qualification.
