# A-0 owner decision packet

Status: **accepted by the project owner on 2026-10-27**, with the instruction
“accept the recommended package”. R1–R16 are accepted with the amendments
and clarifications below. The amended contract is integrated into VISION.
This accepts semantics, not an implemented kernel or a qualified runtime.

A-0b must still demonstrate a usable authoring API and pass independent
fresh-context review before Stage 1 freezes that API.

## Recommendations by ruling

| Ruling | Recommendation | Consequence / required clarification |
|---|---|---|
| R1: transaction savepoint | Accept, clarify staged effects | Refusing a command rolls back that command's attempt; the caller can still reveal validation errors. Exceptions poison the outer transaction. External work starts only after successful publication. |
| R2: concurrency and acceptance | Accept, clarify cancellation and queue scope | Keep named preview/download/save presets. Save is serial plus snapshot-associated. Cancellation cannot establish freshness or undo external effects. |
| R3: command status and invocation lifetime | Amend lifetime; accept status access | Provide status to the command builder explicitly. Retire engine resources deterministically; do not make dropping a Scala handle a cleanup trigger. |
| R4: per-cell revisions | Accept | One increment per unequal committed value; changing and restoring within a transaction is no change. Revisions never wrap. Dirty-by-value remains application policy. |
| R5: effects | Accept | Run once after initial settling, then once per committed change; never expose intermediate states. |
| R6: failed derivations/bindings | Amend | Keep domain validation as values and defects as Failed. Use explicit error boundaries/fallbacks; do not silently retain the last good value only in release builds. Failed enablement disables the command. |
| R7: component definition identity | Accept, clarify logical identity | A definition value supplies a stable logical identity in the process, not a persisted native pointer. `withDeps` must not manufacture a new definition on every render. Re-definition diagnostics are best effort. |
| R8: reconciliation | Accept, specify observable atomicity | ForEach mounts once per key with live Inputs. Preserve keyed branches; remount changed unkeyed values. No observer may see intermediate structural delta application. |
| R9: focus intents | Accept | Resolve after settling; rolled-back attempts cannot leave a focus request behind. Unattached targets produce development diagnostics. |
| R10: restoration | Accept | Originator, then eligible ownership ancestor, then window default; preserve focus/activation distinction. Missing targets cannot leave a stale handle. |
| R11: key precedence | Accept | IME, control editing, local shortcuts, then window chords; declared primary-modifier bindings only. |
| R12: input transactions | Accept | One transaction per delivered event after coalescing; complete-stream cannot silently become coalesce-latest under pressure. |
| R13: handler and committed reads | Accept, clarify coherence | Handlers read staged state; hit geometry is the routed committed snapshot. Read-only phase capabilities must not permit transaction mutation or arbitrary undeclared reactive dependencies. |
| R14: unplaced children | Accept | Remain mounted but excluded from drawing, hit testing and accessibility. Cache measurements by constraints; diagnostics must distinguish intentional measurement from accidental repeated work. |
| R15: presentation | Accept, clarify geometry coherence | Presentation stays outside application transactions. Animated transforms must also update interaction/accessible geometry for the same displayed frame. Reduced motion uses the final presentation target without creating application writes. |
| R16: streaming | Accept direction, keep detailed API provisional | Bounded channels with explicit backpressure/overflow and cancellation/disposal behavior. Exercise them in the corpus before freezing the signatures. |

## Accepted amendments

### R1: scope of rollback and worker start

An attempt rolls back all transaction-owned staged changes made within it,
including cell writes, graph edits, focus intents and staged command starts.
A refusal leaves the parent attempt usable; an exception poisons the enclosing
transaction even when caught. No worker launch or irreversible I/O occurs
while a transaction is merely speculative. Accepted work starts after the
outer transaction successfully publishes. Existing external effects cannot
be rolled back. Stage 1 tests must distinguish refusal from defect poisoning.

### R2: queues and cancellation

Serial ordering belongs to a command instance (or an explicitly shared
application queue), not an implicit global filesystem lock. The command's
snapshot is captured at dispatch, before queuing. Preview cancellation is
cooperative; generation/revision checks enforce acceptance even when old work
continues. Save markers move monotonically, and the save preset serializes
external writes through that command. Applications writing the same resource
through multiple commands must coordinate them explicitly. Queues are bounded;
full queues produce explicit refusal rather than silently dropping saves.

### R3: deterministic lifetime and builder status

Expose a stable read-only `CommandStatus` to the command definition builder,
including `running: Signal[Int]` and the last outcome, so enablement can refer
to status without recursively initializing the unfinished command value.

Retire completed invocation scheduling/cancellation/delivery resources after
the terminal outcome has been delivered. A caller may retain the terminal
outcome as ordinary data. Any live observation capability has explicit
scope/dispose lifetime. Scope disposal disconnects observations immediately
and forbids future UI publication; physical worker/resource retirement follows
the existing retirement rules. Neither dropping a handle nor a finalizer is
a required cleanup event. The command retains bounded summary state, not an
unbounded history of every invocation.

### R6: explicit failure boundaries

`Failed` denotes a defect, while validation uses values such as `Either`.
Unhandled failed bindings show a safe unavailable/error state and report the
failure. Development builds may include diagnostic detail; release builds
must not expose exception internals. Applications can provide a deliberate
fallback or explicitly retain a previous value with a visible stale/error
indication. Release builds do not silently change the meaning of a failed
binding by substituting old data. A failed command-enablement expression is
always disabled and reported. The corpus must cover first-value failure,
failure after success, recovery, and failed enablement.

### R7: definition identity and dependency binding

Definition identity is a process-local logical token belonging to the
Component definition. It is never a native address used as a persistent key
or foreign userdata. Key/owner identity remains separate. Dependency binding
through `withDeps` preserves the definition's token; changeable values belong
in Inputs. A recreated-definition diagnostic is a development aid, not a
correctness guarantee on which identity depends.

### R8/R13/R15: coherent observations

Structural delta application is atomic to observers. Handler reads use the
staged transaction state, while targeting uses one complete committed
interaction snapshot. Presentation-only transforms update draw, hit-test and
accessible bounds coherently for that frame; otherwise the visual target
could move away from its clickable or accessible target. Presentation never
becomes an implicit source of application-state writes or graph dependencies.
Layout presentation is explicitly charged to layout. Reduced motion reaches
the presentation target without scheduling an unnecessary animation train.

### R16: bounded delivery

A progress source declares delivery policy and finite capacity. Coalesce-latest
may replace pending progress. Ordered/complete-stream cannot silently discard
items. Worker producers may wait cooperatively for capacity and must wake on
cancellation/disposal; UI and foreign-thread producers cannot block the UI
owner and must return/report an explicit capacity failure. Closing a channel
prevents further publication. Exact public signatures and pressure examples
remain an A-0b/C1.6a design obligation, not a completed implementation claim.

## What acceptance authorizes next

1. Fold the accepted behavioral contract into VISION, including related
   lifetime, input, frame-coherence and bounded-delivery clarifications.
2. Revise the provisional skeleton and the existing authoring corpus against
   that contract, preserving explicit unimplemented markers.
3. Add compile examples and negative cases covering the missing features;
   record each remaining API awkwardness as changed or deliberately deferred.
4. Run JVM/Native checks and authoring compilation, then the required
   independent fresh-context review. Compilation is authorability evidence,
   not a passing implementation of the behavioral laws.

This acceptance does not adopt the upstream runtime as the production default,
relax the 50 ms gate, or claim the Stage 1 kernel exists.
