# Conditional Native qualification and initial-alpha delivery

The owner authorized the five-step course: qualify the conditional candidate,
arrange the original quiet-host gate, diagnose a qualifying failure, obtain
trap-path runtime review, and make an explicit delivery-order decision.
Work is isolated in `/private/tmp/sirin-native-qualification-20261102`, branch
`native-qualification-20261102`, from `c049658`. The shared dirty checkout and
all earlier candidate artifacts are preserved.

## Candidate verification and fresh bounded check

The preserved event binary still has SHA-256
`87d7a7a94d48f21646053915415e31702517cf984a78b5b4f8c79287d2c2e5d9`.
Its historical build receipt names source `1fc105e`, the full pinned toolchain
at upstream `fa4a9e6ac9dab73ab4d476d6cc8c0cdc422c25f7`, release-fast Immix,
and `SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0` during linking. Symbol inspection
again finds no yieldpoint-trap symbol. The runtime JAR hash still matches
`819a43ed28cf90e09822e5695a079b53f7fbb4db7aa9677f1ba4871fc144df35`.
The GitHub connector independently confirms that merged allocator fix #5128
has that merge commit. This does not qualify a later upstream revision.

Before execution, a new protocol fixed one standalone `120 4 2048 true` check
and a maximum of three serial event-gate trials. The standalone conditional
binary matches its historical hash
`3e2e71e9eb0593f3d67ce8e739bdbb6fcd01beeec1f0b619fd4874fd44a568e5`.
The fresh check completed in 120.22 seconds under the unchanged 128M heap cap,
15-second GC synchronization timeout and 135-second external deadline.
It completed 64,612 collections and 1,590,481,409 allocator iterations.
All 24 periodic samples report heap capacity 35,790,848 bytes; surviving
arrays pass the original endpoint assertions after join, retaining 4,297,736
payload bytes. This is a bounded correctness result on a shared host, not a
latency or long-term memory qualification.

## Gate runner and real preflight

`probes/gc-repro/qualify.py` freezes its own/helper hashes and protocol before
execution. It verifies the preserved binary hash and supported host, clears
inherited GC/DYLD overrides in children, then runs the existing preflight.
Each of three prospective trials requires all six samples to meet 80% idle CPU
and one-minute load/logical CPUs at most 0.5, with ten-second sample spacing.
Only a passing preflight may launch `soak 600 4 1000`, with the original 128M
cap, 15-second synchronization timeout, 620-second external deadline and strict
50 ms event/ticker assertions. There are no readiness or latency overrides.

Exit zero alone is insufficient: copied receipts and output must contain the
original assertions, correct parameters/duration, allocation progress, bounded
reported heap, and no lost/refused events. The probe's unrounded nanosecond
assertion remains authoritative when printed milliseconds round to 50.000.
The runner stops on the first unavailable host, measurement error or gate
failure. It cannot retry until green or qualify only the successful prefix.

After all builds finished, the real preflight ran from 14:53:01 to 14:53:54 UTC
on November 2. All six samples failed: idle CPU was 68.87%, 61.69%, 66.82%,
65.50%, 65.29% and 69.11%. One-minute load was 17.42 down to 9.83 on ten
logical CPUs, also exceeding the allowed 5.0. Recent build load can affect
that average; the contemporaneous idle samples independently fail readiness.
Exit status is 2, `host_unavailable`, qualification false, gate_started false.
No ten-minute trial, retry or unrelated-process termination occurred.

There is therefore no new qualifying latency failure to diagnose. The earlier
busy-host 600-second failure remains preserved and unexplained. A future
qualifying failure must be retained before a separately declared diagnostic
run correlates event stalls with collection/synchronization and scheduling.
That diagnostic must not replace the original uninstrumented acceptance run.

## Runtime review and delivery decision

A self-contained [maintainer report](2026-11-02-native-upstream-report.md)
includes the standalone source, build files, saved-mask patch, causal results,
known limitations and precise ownership/nesting/main-mutator review questions.
It cross-references upstream #5051 and #5046 without claiming identical causes.
The report remains local pending explicit publication approval; no independent
maintainer review or patch acceptance is claimed. The connected GitHub account
is `bbuchsbaum`, while the repository selects `canardlapin`; the account-specific
CLI confirms `canardlapin` for any subsequently approved public issue.

The [delivery decision](../design/decisions/2026-11-02-initial-alpha-delivery.md)
selects a macOS/JVM initial alpha under the owner's authorization to execute
step 5. Native remains experimental with its original runtime and provider
gates. The missing quiet-host result is not treated as a failed conditional
runtime. Runtime defaults, dependency caches and kernel policies are unchanged.
VISION, the execution sequence, alpha plan and status page state the new order.
An older VISION module paragraph incorrectly described current JVM execution
as headless; it is reconciled with the implemented JDK25 development provider
and the updated platform-status section in this same design revision.

## Verification and artifacts

Nine Python tests pass. Their deliberate receipt/process simulations verify
failure preservation, busy-host refusal, changed-binary refusal, original
assertion requirements, parameter/metric checks and the three-trial condition.
These are qualification-tool tests, never passing native latency stubs.
`sbt testJVM testNative` exits 0: 476 JVM and 476 Native tests pass. No existing
test, runtime pin, timeout, concurrency setting or acceptance bound was changed.

Artifacts are under `/private/tmp/sirin-native-gate-20261102`: prospective
protocol, symbols, bounded-check logs/receipt, runner tests, shared regressions,
all six raw preflight readings, gate summary and upstream-source evidence.
The local review bundle contains source/patch/protocol material; no compiled
binary or private runtime JAR is required to read it. Its SHA-256 is
`f0ce535fcec6da9d2eb5a9b50ec64391c10c562451a547e8cf02a8fe1e48e86e`.
The final 41-file artifact inventory has SHA-256
`b9e94cd2eade1e5aebd295f52966befdd147958ffa5c8d81dd420b47b4555d9c`.
It includes the final code/design digests, raw gate/refusal evidence and review
bundle. The shared checkout's tracked diff still has SHA-256
`1bfa8a28e367ba1c946dcaf2e7febba495c95b8c52127c2379ac590320dfa588`.

Tracking: P0.6 `bd-01M5Z3K47RQTRDXEY7Y2P8TMJ3`; delivery order
`bd-01M66B73BRTV11D479KARSA9Y8`. Runtime qualification, external review and
Native application delivery remain open. No merge or push is performed.
