# Live Inputs and keyed headless reconciliation, 2026-10-27

Based on topology/lifetime checkpoint `91a0ceb`. This completes the authorized
bounded headless reconciliation tranche; full C1.5b reparenting and Stage 1 remain
open. The independent sirin-graphics working tree is excluded from this checkpoint.

## Implemented and reviewed

Component descriptions now mount with live read-only Inputs, instance-local cells,
effects and leases. Same-key ForEach replacement/reordering retains local draft
state, as does a same-key keyed Switch update. Definition, dependency reference,
owner, placement key or incompatible structural schema changes remount. Ordinary
owner changes reset state. Explicit outlet/reparent operations remain deferred.

Structure, signal values, Inputs, source revisions and disposal publish atomically.
Refused attempts restore descriptions and speculative reads; root refusal, duplicate
keys, builder defects and exhausted budgets roll back the candidate. Failed source
bindings expose Unavailable and retire descendants; recovery mounts fresh state.
Read-only construction/key/equality guards prevent captured capabilities escaping.
Scoped callbacks and queued effects retain the prior lifetime guarantees.

The synchronous keyed source implements keyedSnapshot, snapshot, lookups, filtering
and transactional append/insert/remove/move/replace/update/setAll. It rejects
invalid key changes and duplicate keys. Missing remove is idempotent; operations
requiring an existing key refuse. Collection events and filtered-source revision
streams explicitly remain deferred. This is a small full-snapshot path, not a
virtualization/minimal-delta qualification.

The independent source reviewer found and re-reviewed fixes for speculative cache
invalidation, ancestor provenance, custom equality, guarded key equality, branch
snapshot identity, closure-allocation identity, erased schemas and Inputs.attempt
source failure. See `../design/decisions/2026-10-27-live-inputs-reconciliation.md`.
Final verdict: no remaining blocker in the bounded implementation/oracle, subject
to final JVM/Native checks. The reviewer ran no builds; main session ran the checks.

## Verification

`sbt testJVM testNative examples/compile probeWindow/test probeThreads/test`
exited **0** on macOS arm64, Java 21, Scala 3.7.4, sbt 1.12.14 and the normal
Scala Native **0.5.12** pin. The authoring corpus compiles against the updated API.

| Suite | JVM | Native |
|---|---:|---:|
| Core | 7 | 7 |
| TransactionSuite | 24 | 24 |
| TopologyLifetimeSuite | 28 | 28 |
| ReconciliationSuite | 29 | 29 |
| ReferenceAgreementSuite | 1 | 1 |
| LifetimeAgreementSuite | 2 | 2 |
| ReconciliationAgreementSuite | 2 | 2 |
| ABI | — | 14 |
| Mailbox | — | 13 |

**213 owned test executions, zero failures.** The aggregate aliases also ran the
concurrent graphics module: 37 tests per platform, **287 total executions**. Those
74 checks are observations of the other agent's working tree, not graphics
qualification or inclusion in this commit. The tested build.sbt is hashed in the
manifest but is left with that agent's changes. Earlier explicit owned JVM checks
also passed. Logs and source/build SHA-256 hashes are in `c1-reconciliation-20261027/`.

Each platform runs 300 reconciliation programs of 40 steps: **24,000 generated
steps across JVM/Native**. The pure immutable full-state model imports no engine,
Signal or reconciliation code. Programs include row assignment/reorder/replacement,
local draft writes, speculative Inputs reads, rebuilt wrapper lambdas, accepted
and refused attempts, root refusal, caught defects, bounded effect draining and
generation-tagged delayed delivery. The comparison includes transaction results,
staged read values, ordered keys, logical component identity/generation, live
props/drafts, source revision, queued effect counts, and exact registration-ordered
observation/effect and reverse-retirement release traces. A fixed script covers
same-key replacement, reorder, staged reads, rebuilt lambdas, remove/readd and
stale delivery through the same harness.

Existing randomized expression and lifetime models still pass: **18,000 generated
transactions** and **30,000 lifecycle steps** across JVM/Native. Counts describe
one final run, not repeated validation runs added together.

Deterministic engine cases separately cover keyed/plain Switch, dependency reference
identity, independent boundaries, source rebinding and staged nested reads,
rekey/removal/retype invalidation, schema changes, source failure/recovery,
construction guards, builder compensation, duplicates, equality cutoffs/signed
zero, budget failure and 50-cycle structural churn. Compile controls cover the
read-only Inputs surface and complete generic/nested/union/abstract/dependent schema
evidence, with genuine static-type and explicit-evidence positive controls.
These cases are not claims of randomized coverage. Churn checks engine-owned
counts rather than process heap/RSS.

## Limits and next work

MountedSnapshot is a structural inspection API. Leaves are opaque; no painting,
layout, native integration, focus or widget behavior is qualified. ForEach/Switch
retained keys keep the originally built description; changed dynamic captures must
flow through Inputs/dependencies or a new placement key. SchemaId instances must
distinguish incompatible types; generic helpers forward complete evidence.

Collection reconciliation uses full snapshots; speculative row provenance can
scan the list, and the host still copies/traverses graph metadata. No large-list
or frame-time performance claim is made. Events, settle tracing, tasks/commands,
channels, deadlines, explicit reparenting and native/GPU retirement remain open.
The next kernel tranche is task/command scheduling with controlled latency and
independent acceptance-policy reference coverage. Full Stage 1 additionally needs
all remaining laws, semantic review and authoring review against the real API.
The upstream GC pause gate remains separate and unchanged; no soak was launched.
