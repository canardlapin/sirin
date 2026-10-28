# Responsive JVM latency follow-up — 2026-10-28

Implementation and functional checks pass. **Action-latency qualification remains
open.** The sole quiet preflight refused to launch; the sole explicitly diagnostic
run also missed the unchanged action p95 target. No retry until green occurred.
This is an incremental isolated candidate after `d7c4e48`, on
`codex/responsive-workbench-20261028` in `/tmp/sirin-responsive-20261028`.
The shared checkout and concurrent G2 graphics source were not replaced.

## Changes and bounds

- The FFM retained handle keeps immutable target metadata after native upload,
  releasing the compiled float array and complete managed drawing/resource graph.
- The tile cache can reuse completed prior variants by complete position,
  signature and target. Active tiles alone compose the frame. Inactive variants
  evict in LRU order; incomplete cancelled entries cannot be reused. Size/scale/
  device changes clear the cache.
- Each cache still admits at most32 native programs /24MiB. Each now explicitly
  counts at most256 snapshot entries and1,048,576 reserved output pixels,
  including incomplete and inactive entries. Retiring a base snapshot invalidates
  every composite entry/native program referring to that exact source. Composite
  keys cannot keep an evicted base image outside its readback budget.
- No kernel, Native callback, operation/storage/sample-work budget or frozen
  latency target changed. The shared renderer, shader and Native bridge remain
  at the prior integrated baseline. The JVM adapter still refuses shared-asset
  programs it does not implement.
- Startup, queue, worker and native presentation timings are distinct. Phase CSV
  rows retain actual input, target ID, visible target label, cumulative JVM GC
  counters and geometry. Timings overlap or omit intermediate turns; they are not
  an additive decomposition of input latency.

## Verification

[Platform log](sirin-latency-platforms.log): required `testJVM testNative` pass
**614 cases**, zero failed/ignored, and all13 authoring examples compile. The
Native testkit suites run serially with unchanged property counts/assertions.
The separate79 native text/Unicode/paragraph/render probe cases passed in the
[prior baseline](../responsive-host-20261028/README.md); they were not rerun here.

[Desktop log](sirin-latency-geometry-desktop.log): **19 cases pass**, including
all previous controls and new pixel-equivalent reverted variants with zero
upload/readback, pixel/entry/native inventory eviction, source retirement,
wrong-DPI refusal and sticky resize refusal. Total fresh Scala cases: **633**.
[Python controls](python-controls.log):6 pass, including wrong geometry, missing
samples, nonfinite values and a claimed pass that misses p95. Review found no
remaining acceptance blocker after adding fixed-geometry validation.

The [native resize control](resize-refusal/launch.log) changes the actual window
from600x360/1200x720 to640x400/1280x800 after its first presentation. It exits1,
keeps `geometryStable=false`, and writes a failed partial receipt with **zero
warm-up or measured samples**. The runner independently validates all240 ordered
raw/phase samples, dimensions, finite nonnegative latencies and unchanged
percentiles; file presence or a claimed pass alone cannot qualify a run.

The rebuilt app was also exercised through native CUA: Tab/Shift-Tab,
Enter/Space (count0 to2), retained document movement with focus, pointer
activation (count3), disabled pointer/Enter refusal (count stays3), repeated
toggle restoration, PageDown and wheel scrolling through Record100, actual drag
resize to1280x800 client pixels, and native close. The final app inventory had no
Sirin process. Screenshots were inspected in the session; no saved OS screenshot
artifact is claimed. Custom accessibility controls remain a separate gate.

One launcher attempt selected JDK21 despite a shell JAVA_HOME override and failed
on JDK25 FFM methods `Arena.allocateFrom` / `MemorySegment.getString`. The final
command uses explicit `sbt --java-home .../temurin-25.0.4.1+1.jdk/Contents/Home`;
the final log identifies25.0.4.1 and passes. No source workaround was introduced.

## Preserved measurement results

Frozen fixture:100 records,600x360 logical /1200x720 physical, default128px tiles,
20 warm-up samples/class,100 measured/class. SDL events to matching successful
presentation remain the endpoint. No benchmark PNG I/O. The new phase CSV has
240 rows /19 fields; every sample has matching geometry and nonnegative phase
times. Benchmark geometry is checked on every received event and presented frame.

The runner uses the packaged Java runtime directly, with no sbt/compiler process
running for the experiment. It identifies package hashes before preflight. A
normal qualification requires three interval `top` samples, each at least85%
CPU idle and load1m at most5 on this10-logical-CPU machine.

[Quiet refusal](quiet-refusal/invocation.json),19:38:14 UTC: CPU idle
36.15/35.43/43.50%, load1m12.53/13.29/12.25. Preflight exits2;
`status=not_run`, `qualified=false`; **no benchmark was launched**.

[Diagnostic invocation](diagnostic/invocation.json),19:38:43 UTC: preflight CPU
idle42.70/28.45/5.47%, load1m11.60/10.50/10.14. This mode always remains
unqualified, even if latency targets pass. Here the benchmark itself exits1.
All counts complete, geometry stable across1213 observations; action p95 fails.

| Class | p50 ms | p95 ms | p99 ms | max ms | Frozen p95/p99 | Result |
|---|---:|---:|---:|---:|---|---|
| action/focus |27.778|52.428|68.965|150.970|50/100ms|p95 fails|
| scroll |19.285|40.456|43.642|115.302|100/200ms|passes|

Seven action samples exceed50ms and one scroll sample exceeds100ms. Preserve the
[raw CSV](diagnostic/benchmark/input-to-present.csv),
[phases](diagnostic/benchmark/phases.csv), [receipt](diagnostic/benchmark/receipt.txt)
and [complete log](diagnostic/benchmark.log). The prior complete run had action
p95=63.745ms and16 action samples above50ms, but differing host load prevents a
controlled speedup claim.

[Joined analysis](diagnostic-analysis.json) finds five slow actions on Activate
and two on Toggle activation. Toggle Enter mean is29.434ms (worker24.621ms),
versus the saved prior50.795ms (worker44.023ms). This is descriptive only. The
seven slow actions spend47–147ms in their last render worker; the profile includes
GPU/native-call waits and scheduling, so it is not pure GPU execution time. Queue
and presentation timings are separate. The worst action has146.970ms worker
and1.327ms presentation. GC counters and correlations do not establish a cause.
99 of100 measured scroll presentations report zero program upload and readback;
the first measured scroll uploads and reads back changed tiles.
this exercises reuse but does not remove final CPU-to-surface presentation upload.

Main entry to first successful presentation is2374.880ms; window setup529.084ms,
render-session setup206.767ms. This boundary excludes Java process launch and JVM
startup before main; the nested/concurrent setup figures must not be summed.
It is not an end-to-end process cold-start qualification.

## Reproduction and next gate

See [desktop instructions](../../../desktop/README.md) and the
[qualification plan](../../plans/2026-10-28-latency-qualification.md).
`python3 -B desktop/tools/run-latency.py` is the qualification command after
packaging and stopping this candidate's build activity. It refuses busy hosts
rather than treating diagnostic data as qualification. Do not stop unrelated
user work or rerun opportunistically. Use the same frozen candidate once a
quiet machine is available; if action p95 still misses, profile changing counter/
enabled-state frames and native dispatch/readback on that qualified environment.

`source.sha256` and `tested-source.tar.gz` identify the exact source/build/fixture
inputs, excluding generated targets, vendor binaries, evidence and Mote.
`package.sha256` identifies the app launcher, Java runtime binary/metadata,
class jar, native library and Scala jars. Package hashes match the measured app,
resize control and visible interaction checks. This remains a local development
app image with absolute local fixture paths.

Mote latency `bd-01M61Y6MTGD458A4MYNW92PG6G` and parent responsive tranche
`bd-01M61Q51R1ZBB4ZGXGK6XN6HDC` stay open. Monitor-scale/occlusion/quiet power,
Native GC/UI, text editing/IME/accessibility, Windows/Linux and the matched
ScalaFX usability/elegance comparison remain separate acceptance gates.
