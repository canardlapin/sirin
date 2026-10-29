# Retained canvas and controlled device recovery

Completed on `codex/canvas-recovery-20261028` from `1ef2032`, in the isolated
`/private/tmp/sirin-gpu-host-20261028` checkout. The dirty shared root and earlier
evidence were preserved. [Frozen contract](../../plans/2026-10-28-canvas-recovery.md),
[source hashes](sources.json), [environment](environment.json).

The worker retains up to 256 source-geometry entries and 4MiB of declared
source/flat-point payload. Translation reuses completed geometry under precision
checks; edits invalidate changed path identities, and scale/shear rebuilds.
Cache hits retain the original point and temporary-outline budget charges.
Glyph masks and decoded images also reuse their existing bounded caches.
Unchanged canvas requests skip preparation, compilation, uploads and dispatch.

Inspection, flattening, stroke expansion and tessellation share a cancellable
64million-work-unit control. Interval sweeps skip disjoint edges and inactive
scan rows without raising the original conservative 16million-pair admission
bound or changing boundary arithmetic. Cancellation refuses partial publication;
completed source entries remain reusable. GPU calls remain synchronous, with
checks between calls. This is cooperative cancellation, not hard real-time
preemption. The existing mailbox now quiesces and retires its backend before the
same Scala-created worker opens a replacement. Kernel policies are unchanged.

## Pixels, reuse and cancellation

The [16 PNG/raw pairs](pixels/) cover initial, fractional pan, zoom and edited
path variants at 1/1.25/1.5/2x. The unchanged BatchOracle policy independently
identifies exact-boundary ties; [its counts](pixels.json) are preserved. A separate
all-byte comparison of the saved raw pairs finds **zero error on every pixel**,
including those ties. The four-scale reference test and all earlier GPU tests
pass without relaxed tolerances.

Tests verify 100-path translation reuse, one-path selective invalidation, scale
rebuilds, unchanged zero work, no new image/glyph texture upload on pan, stricter
budget refusal on cache hits, and cache bounds over 100 edits. Actual device
destruction rejects old readback/compilation/generation use, invokes the dedicated
native loss callback, and recreates byte-identical pixels with the CPU cache
reused. Twenty in-flight cancellation/retirement/reopen cycles use the same worker
and leave no stale result. Native owners, frames, tiles and health records return
to zero after close.

[Cancellation observations](cancellation.json) record a request cancelled on
check251 after250 work units: 0.519ms on JVM and 0.619ms on Native in these
single observations, with no completed cache entry. Twenty worker retirement
observations range from 0.017 to 1.050ms. These are fixture observations, not
deadline guarantees for arbitrary work or an in-progress native call.

## Actual packaged controls and device-loss limit

[Packaged controls](controls.json) all exit0: canvas pan/zoom/edit/destruction/
recovery/native-close, ordinary workbench destruction/recovery/close, and ten
partial-init/late-callback controls. [Package hashes](package.json) identify the
same package used by the diagnostics. These controls used actual windows;
the PNGs are GPU readbacks, not OS screenshots or a visual-approval claim.

Callbacks touch native-owned atomic health and wake SDL through copied event
IDs; no Scala callback runs on a foreign thread. Recovery cancels and drains
worker work, retires frames, replaces the lost device/instance/surface, and
requests the latest immutable recording. Old generations cannot present.
Three consecutive replacements without a successful presentation fail closed.
Workbench activation waits for a complete new presentation.

**Controlled destruction is verified; spontaneous hardware-loss races remain
unqualified.** On the pinned wgpu-native29.0.1.1, destruction alone did not deliver
the dedicated callback. Buffer creation reported `Parent device is lost`, and
encoder creation delivered the real loss callback. The explicit control uses
that encoder path and checks health before submission. A trial empty submission
to the destroyed queue aborted in upstream `wgpuQueueSubmit`; its
[original log](history/loss-3.log) is retained. The guards prevent that tested
sequence, but cannot establish absence of every asynchronous hardware-loss race.
The [pinned upstream source](https://github.com/gfx-rs/wgpu-native/blob/v29.0.1.1/src/lib.rs)
and its SHA in the environment receipt identify the reviewed implementation.

## Canvas latency remains diagnostic

The packaged 100-path worker diagnostic records 184 completions: per scale, one
cold frame, five unchanged warmups, then ten each unchanged/pan/edit/zoom.
It excludes OS input, queue delay and presentation. The workload contains curves,
gradient strokes and an image. A separate test checks shaped glyph reuse.
[Raw samples](canvas/frames.csv), [counts/transfers/work/percentiles](canvas/summary.json).
Nearest-rank p95 with ten samples is the maximum; these are small diagnostics.

| Scale | Cold, one ms | Unchanged p95 ms | Pan p95 ms | Edit p95 ms | Zoom p95 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 496.822 | 0.574 | 101.856 | 16.921 | 248.970 |
| 1.25 | 156.626 | 0.049 | 78.911 | 21.425 | 163.266 |
| 1.5 | 172.231 | 0.026 | 107.225 | 26.439 | 194.803 |
| 2 | 228.056 | 0.023 | 168.936 | 42.680 | 302.945 |

Pan incurred no source-geometry misses; unchanged frames compiled/uploaded
nothing. Edits changed one or two tiles. Fractional pan and zoom still
rerasterize moved coverage, so these dense-scene results do **not** establish a
50ms canvas target. Retaining GPU camera transforms/coverage across these moves
is the next performance tranche. Maximum measured request work was4,140,499,
below the unchanged64million limit.

The ordinary [host diagnostic](host-diagnostic.json) separately passed the frozen
600x360logical/1200x720physical, 100-record workload with40 warmups and100
measured samples per class. Action p95/p99:18.106/25.142ms; scroll:17.041/17.747ms.
[Independent receipt validation](latency/20261029T014416.694586Z/invocation.json)
passed unchanged sample, geometry and target checks. Main entry to first
presentation was862.867ms, excluding pre-main JVM startup. This run is explicitly
`qualified=false`; no new quiet qualification was attempted. The previous
refused qualification remains immutable in the preceding GPU-host receipt.

## Verification and preserved failures

**749 distinct Scala cases passed** across the required `sbt testJVM` and
`sbt testNative` aliases, font/Unicode/paragraph/GPU probes and final desktop
tests. The full matrix log passes both aliases and all probes, then records a
failure in the new desktop quiescence test harness. A worker-side MUnit
assertion and blocking calls inside main-thread MUnit assertions caused a
framework lock interaction. Worker invariants now use `require`; blocking waits
finish before main-thread assertions. The same5s bounds and invariants remain.
The final desktop refresh passes30/30, Native GPU34/34, and the extra pixel/
cancellation refresh passes4 JVM +4 Native +5 desktop cases. Repeated cases are
not added to the749 total. Python receipt tests pass6/6;13 examples compile.
[Validation summary](validation-summary.json), [logs](validation/).

Earlier [harness/callback experiments](history/) remain, including the fatal
native queue experiment. The [initial dense canvas pan](history/scenario-1.log)
refused at64million work units; interval sweeps resolved it without increasing
that cap. An initial shutdown-test timeout remains in the full matrix rather
than being relabeled a successful full invocation. No failed run was deleted.

Reproduce with JDK25 and the pinned fetched Native artifacts:

```sh
sbt --java-home /path/to/jdk25/Contents/Home testJVM testNative textNative/test probeUnicode/test probeParagraph/test probeRender/test desktopJVM/test
sbt --java-home /path/to/jdk25/Contents/Home examples/compile desktopJVM/desktopPackage
desktop/target/package/Sirin.app/Contents/MacOS/Sirin canvas scenario
desktop/target/package/Sirin.app/Contents/MacOS/Sirin recovery
python3 -B desktop/tools/run-latency.py --diagnostic
python3 -B docs/evidence/canvas-recovery-20261028/verify.py
```

The verifier checks source/artifact integrity, every saved pixel byte, frozen
canvas sample counts/counters and the host receipt. It does not perform a new
GPU run or close quiet-run, hardware-loss, other-platform or browser-parity gates.

Mote follow-ups: `bd-01M62P15FQXD2Z9Q2NHN61G1AD` tracks GPU camera/coverage
performance; `bd-01M62P15NZ98V8TTFSV5GDVE21` tracks spontaneous-loss races.
The bounded implementation issue is `bd-01M62JZ0C1PCVE9WJKJ2CCXEA5`.
