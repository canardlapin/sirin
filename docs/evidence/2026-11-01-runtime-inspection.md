# Bounded committed runtime inspection

The runtime now provides an owner-thread `inspect` snapshot of live scopes,
signal IDs/dependencies, committed cell revisions, scalar state, last committed
value-change causes, command running/refusal state, work flags and retained
transaction traces. The API and limits are described in
[runtime inspection](../tooling/runtime-inspection.md); its scope and design
boundaries are in the [decision](../design/decisions/2026-11-01-runtime-inspection.md).
This is the runtime foundation, not completion of the visible developer inspector.

Snapshots neither attach roots nor evaluate user code, advance work, collect
worker results or retain application payloads. Diagnostic IDs use a separate
counter and survive node copies without affecting kernel identity. Command
registration rolls back with construction and is removed at committed owner
retirement. Copied text uses detached bounded character buffers. Failure history
retains bounded strings and class names, never exception objects.

Seven shared tests cover stable IDs and unchanged graph/evaluation counts,
throwing application formatters, rollback, committed change causes, command queue
refusal, scheduler state, completed-but-uncollected worker results, bounded output,
surrogate-safe truncation, value-only snapshot structure, owner retirement and
rejection during transactions/read-only callbacks. Two test-compilation attempts
caught fixture errors (constructing Work inside a transaction and
using the wrong worker-shutdown method); those logs are retained. No assertions
were weakened to obtain a pass.

A further test uses the actual DocumentWorkspace model. Opening a document,
clearing Title and dispatching Save produces `command disabled`. Inspection
finds `document.save`, its false enabled value and the empty-title dependency by
walking 15 nodes from the command's enabled node. Repeated capture preserves all
lifetimes and the exact inspected state. The test runs on both JVM and Native.
This is a model-level diagnosis, not a new packaged window observation.

The frozen workbench contains 99 scopes, 730 materialized nodes, 559 dependency
edges and 28 commands. Local runs capture 25 snapshots at default budgets; the
logs record each run's median and p95. These measurements describe explicit
snapshot capture, not presentation latency or the total cost of instrumentation.
The separate bookkeeping comparison uses the same frozen workload against
baseline `3561504` and this candidate: 112 live nodes, 16 commands, 2,000 warmup
writes, then eight batches of 1,000 writes, without calling `inspect`. The upper
middle batch was 45.127 ms on baseline and 44.799 ms on the candidate. This single
local paired run does not establish zero overhead or a latency gate. The source
archive, all per-batch samples and the initial sbt project-scoping error are kept.
Both runs used Temurin 25.0.4.1 and `engineJVM / Test / runMain`; the frozen probe
source was added with `set Test / unmanagedSources += file(...)` after selecting
`project engineJVM`. No rendering/readback/upload is in this workload.

Full validation passed 462 JVM, 462 Native and 151 desktop tests, examples
compilation, production packaging and all nine Native accessibility ABI entries.
After the bounded-string buffer copy was tightened, the seven inspector tests
and 13 complete workbench tests passed again on both JVM and Native. The final
workbench measurements were JVM/Native observations recorded in the raw log;
no timing threshold was introduced or loosened.

The production image passes dependency/font/signature/library-loading checks.
Its InspectionText class matches the final compiled class byte-for-byte. A Native
executable reaches all nine ABI entries using the exact packaged dylib, with the
loader path in the receipt. No new native entry point or foreign-thread callback
was added. The package has not been launched; no UI acceptance is inferred.

Raw logs, the baseline source archive, benchmark source and receipts are retained
under `/private/tmp/sirin-runtime-inspector-20261101`. Pending repaired-popup
launch approval is unchanged; this work does not replace that acceptance check.
Actual IME/VoiceOver, physical input, a visible inspector, full per-phase frame
causality, render/cache views, matched ScalaFX comparison and qualified Native
application delivery remain open.

The 29-file source/log/package receipt manifest has SHA-256
`03edf8fb215f47e3336efc9acf19460e2ed5507fb4ea66a6e35e4758ce5bf659`.
The expanded baseline build tree is excluded; its frozen source archive is
included and hashes to
`67cda90a0ed831f3792bbd58090f990bca2eeea58e1c35298224fc0e20473e3b`.
Alpha5 tracking issue: `bd-01M65371PMXY6DG0YZNVJ15R00`.
