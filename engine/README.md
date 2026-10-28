# Headless core

The JVM/Native `HeadlessRuntime` implements transactions, static and dynamic
signals, scoped ownership, observations, queued effects and keyed headless
component reconciliation. Widget hosting, tasks, commands, channels and event
streams are later stages. Calling those unsupported `Scope` methods raises `KernelError.Deferred`;
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
static headless lifecycle boundary. `host.mount(owner, view)` separately mounts
component, stack, ForEach and Switch descriptions and exposes a read-only
`MountedSnapshot`; opaque leaves are retained without rendering.

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

`Component` builders run once per mount with a stable read-only `Inputs[P]`.
Same-key ForEach replacement/reordering and same-key keyed Switch updates deliver
new Inputs while retaining local cells and effects. Changed definition tokens,
dependency references, placement keys, owners or incompatible input schemas
remount. Ordinary root description updates are transactional through
`MountedView.update`; duplicate keys or builder defects roll the candidate back.
Failed sources expose `MountedSnapshot.Unavailable`, retire their subtree and
mount fresh state on recovery. There is no native outlet/reparent implementation.

Staged reads from Inputs follow the current source and ancestor placement,
including source rebinds and removals. Committed propagation respects source and
output equality. Construction can declare cells/children/observations/leases in
its new ownership subtree; captured writes and foreign-owner operations refuse.
ForEach/Switch closure allocation is not boundary identity: retained keys keep
originally built descriptions. Supply dynamic captures through Inputs or explicit
component dependencies, or change a boundary key when a new mount is required.

ForEach/Switch require `SchemaId` for complete input and key types. Concrete static
types derive it; generic helpers forward the relevant evidence to their callers.
Abstract, local-object and value-dependent types need explicit lawful schema IDs
that distinguish incompatible types. Sequence and CollectionSource row storage
representations also have different schemas. A boundary retains its initial
Equality for its lifetime.

`scope.keyed` supports transactional synchronous keyed mutation and explicit
`CollectionSource.keyedSnapshot`. This is a small O(n) source/reconciliation path,
not virtualized realization or minimal change delivery. Collection changes and
filtered-source revision streams remain deferred. See
`docs/evidence/c1-reconciliation-20261027.md` for tests, review and limits.
