# Available-host workbench acceptance — 2026-10-29

**The responsive-workbench latency gate passes on the available host.** The owner
explicitly directed: “well, not gonna get much quieter. please proceed”. This
waives the numerical idle precondition for this tranche; it does not change the
fixture, sample counts or latency limits. The current VISION and qualification
plan record that scope. Formal quiet-run qualification is not claimed.

The selected implementation is `ada4e2b`, the retained-GPU/canvas/recovery
successor in `/tmp/sirin-gpu-host-20261028`. Acceptance documentation is on
`codex/available-host-acceptance-20261029`. The completed parallel branch, original
responsive checkout and dirty shared source are preserved. This turn changes
only documentation, evidence and this actor's tracker operations. No rebuild,
application source change, push or unrelated process shutdown occurred.

## One run per frozen package

The prior frozen responsive package was measured first, as requested. The tracker
then revealed the newer retained-GPU successor. Its final canvas/recovery source,
artifact, pixel and receipt verifier passed, and its package matched the recorded
hashes before measuring. Each package ran once under the explicit host waiver.
These are separate source/package identities, not retries of one failed run.
Both pass; no comparative speedup or causal load claim is made.

The workload stays100 records,600x360 logical /1200x720 physical pixels,
20 warm-up samples/class followed by100 measured samples/class. Actual SDL input
ends at the matching successful presentation. Counts, stable geometry, raw/phase
agreement and nearest-rank percentiles were independently replayed. The existing
receipt validator also passes both runs. No benchmark capture I/O or concurrent
candidate compiler process was used.

| Selected retained-GPU package,100/class | p50 ms | p95 ms | p99 ms | max ms | Frozen p95/p99 |
|---|---:|---:|---:|---:|---|
| Action/focus |16.536|19.681|22.862|36.411|50/100ms|
| Scroll |16.573|17.154|17.823|27.012|100/200ms|

All four limits pass. No action sample exceeds50ms; no scroll sample exceeds
100ms. [Invocation/package hashes](retained-gpu/invocation.json),
[raw samples](retained-gpu/benchmark/input-to-present.csv),
[phase rows](retained-gpu/benchmark/phases.csv),
[host receipt](retained-gpu/benchmark/receipt.txt), [full log](retained-gpu/benchmark.log).
Main entry to first presentation is840.200ms in this run, excluding pre-main Java
startup. It is a single observation, not a cold-start percentile.

Its three preflight idle samples are66.20/73.90/75.53%, load1m3.17/3.08/3.15.
These do not meet the earlier85% idle condition. The runner was deliberately
called with `--diagnostic`; its original `qualified=false` remains intact.
[acceptance.json](acceptance.json) separately records the owner-authorized
available-host decision. Noise is measured rather than assumed absent.

The older CPU-readback package also passes: action p95/p99=44.919/61.258ms,
scroll=17.015/17.197ms. Its action maximum138.247ms and scroll maximum100.730ms
are preserved; p99 is not a maximum bound. Four actions exceed50ms and one scroll
exceeds100ms. Its idle samples74.37/67.79/72.58%, load2.96/2.89/3.06 remain in
[the separate baseline receipt](cpu-readback-baseline/invocation.json). It is
historical; the retained-GPU successor is the implementation to carry forward.

## Evidence and scope

The selected code already has [749 Scala cases and six receipt controls](../canvas-recovery-20261028/validation-summary.json),
including required JVM/Native aliases, final desktop/native GPU refreshes,
controlled cancellation/recovery and pixel checks. Those are prior executions at
`ada4e2b`, not new test passes claimed for this documentation-only turn. The
[pre-amendment integrity check](preexisting-evidence-verification.txt) verified the
existing source/artifact hashes and all16 saved raw pixel pairs. This acceptance
adds two actual-window benchmark executions with their240 samples each.

The historical evidence verifier compares its original documentation hashes to
the working tree. New status/waiver documentation intentionally differs. The new
verifier checks that historical source snapshot at `ada4e2b`, requires application
source to remain identical, checks the current package, replays both raw benchmark
receipts and verifies this evidence manifest:

```sh
python3 -B docs/evidence/available-host-acceptance-20261029/verify.py
```

The latency bead `bd-01M61Y6MTGD458A4MYNW92PG6G` and its responsive-workbench
parent `bd-01M61Q51R1ZBB4ZGXGK6XN6HDC` are closed under this scope. The waiver no
longer blocks further development. Prior failed runs and quiet refusals remain
immutable. This does not establish other-platform performance, browser parity,
ScalaFX usability superiority, dense canvas pan/zoom targets or spontaneous
hardware-loss safety. GPU camera/coverage performance and spontaneous-loss races
remain tracked separately; editing/IME/accessibility remain subsequent scope.
