# Retained GPU camera motion — 2026-10-29

Completed bounded implementation on `codex/camera-retention-20261029`, isolated
at `/private/tmp/sirin-camera-20261029`, from accepted checkpoint08d9837.
The shared root and earlier candidate/evidence remain untouched.
[Contract](../../plans/2026-10-29-camera-retention.md), [sources](sources.json),
[validation](validation-summary.json), [package/run identity](measured/invocation.json).

## What changed

Two exact-scale scene programs retain canonical GPU geometry, clips and paint.
Camera translations update32 uniform bytes, without recompiling moved paths.
Switching between cached exact scales reuses their programs and glyph masks;
novel scales rebuild at the requested curve tolerance and glyph density.
Resolved pixels are never magnified to approximate zoom.

World-space tessellation does not prematurely clip geometry to the initial
viewport. Rebasing and canonical f32 relative inputs, admitted only within
tolerance/64, make translated copies share meshes. The original16million pair
admission cap,64million request work, GPU/CPU inventories and output precision
checks stay in place. The frozen100-path fixture uses three mesh builds per
exact-scale variant. Camera arithmetic and paint mappings validate the actual
inverse-camera viewport; precision cases rebuild through the clipped tiled path.
Camera translations are bounded to4096 device pixels per axis. Targets above
640x360 physical pixels use the existing tiled renderer.

Integer-device-pixel pans copy valid overlap and shade exposed coverage.
Same-camera edits copy the previous image and shade conservative changed-fill
bounds; unprovable damage shades the full target. Fractional pans use the exact
new sample grid and shade the viewport. Transient attachments still clear and
the pipeline visits retained draws; this is not a minimal-bandwidth claim.

Each published output is an immutable GPU snapshot. A full snapshot copy costs
width*height*4 GPU bytes per changed frame (up to921,600 bytes here). Overlap
copies add reused-area*4 bytes; dirty-fill updates copy the preceding full output.
These are GPU texture copies, not CPU readback/upload. Unchanged frames export
references without another snapshot or rendering. Existing16MiB exported-output,
three-frame,16-program and192MiB GPU payload bounds remain enforced, with at most
two retained camera variants. Geometry/asset upload counters distinguish these
copies from CPU-to-GPU transfers.

Cancellation never publishes a partial frame. Completed private variants may
remain reusable. Eviction, worker retirement and controlled loss close variants
while previously published snapshots retain their own resources. No kernel,
identity, focus, transaction or application scheduling policies changed.

## Frozen performance results

The final package ran each workload once after the dense-admission correction,
without a concurrent candidate compiler. Raw load snapshots record available
host conditions; no numerical quiet precondition was imposed. The prior owner
waiver and workbench acceptance remain in the preceding receipt.

The warm renderer diagnostic uses320x180 logical pixels at four scales,
100 retained curves with gradient strokes and an image,20 warmups then100 measured
samples each for fractional pan and alternating exact zoom1/1.25. All eight
groups meet the frozen50ms p95/100ms p99 targets. Measured motion compiles no
programs and uploads no geometry or image/glyph assets. It updates32 camera bytes
per changed frame and performs the GPU snapshot copies described above.
[960 raw rows](measured/worker/receipt/frames.csv), [acceptance replay](measured/worker/acceptance.json).

| Scale | Pan p95 / p99 ms | Cached zoom p95 / p99 ms |
| --- | ---: | ---: |
| 1 | 5.181 / 5.301 | 4.899 / 4.977 |
| 1.25 | 3.591 / 6.335 | 3.397 / 3.525 |
| 1.5 | 3.412 / 3.530 | 4.769 / 4.856 |
| 2 | 9.591 / 10.743 | 6.339 / 6.417 |

The separate actual-window benchmark starts at an injected SDL event timestamp
and ends at its matching successful presentation, including handler/queue/worker
and presentation time. Geometry remains320x180 logical/640x360 physical.
It uses20 warmups and100 measured samples per class. Both groups meet50/100ms.
[240 raw rows](measured/input/receipt/frames.csv), [acceptance replay](measured/input/acceptance.json).

| Native input to present | p50 ms | p95 ms | p99 ms | Maximum ms |
| --- | ---: | ---: | ---: | ---: |
| Fractional pan | 16.586 | 17.480 | 18.392 | 22.713 |
| Cached exact zoom | 16.610 | 17.086 | 17.421 | 21.795 |

These are available-host measurements on the Apple M2 Pro adapter, not formal
quiet-host qualification. Worker pre-run idle observations were66.56/72.50%,
load5.51/5.23; native-input observations64.70/73.12%, load5.21/5.21.
No other process was stopped or quiet-host retry made.

**Novel zoom scales remain cold rebuilds.** The unchanged earlier184-sample
workload is preserved separately. Its ten zoom samples include a cold scale
transition: nearest-rank p95/max80.811–114.622ms. Cold initial frames are
80.013–335.100ms in these single observations. Pan p95 is5.383–6.806ms and
edit p95 is20.727–33.718ms. These small diagnostics do not establish continuous
uncached zoom responsiveness or a causal speedup relative to a different run.
[Raw samples](measured/baseline-workload/receipt/frames.csv), [summary](baseline-workload-summary.json).

## Pixels, lifecycle and regression checks

[49 raw reference/actual pairs and PNGs](pixels/) cover16 original canvas
variants,24 mixed-content camera variants, eight full100-path dense variants,
and a selective-edit comparison. All pass the original independent BatchOracle
<=2-byte policy; its exact-boundary mask policy is unchanged.
[Per-sample statistics](pixels.json) preserve checked/excluded counts and raw
all-byte maxima. Maximum error on checked pixels is1 byte. All eight dense pairs
and the selective edit are byte-identical over every pixel. The edit reuses5820
of6144 pixels and checks the complete new output against the reference.

Four mixed-content samples have all-byte maxima19–32 on excluded exact-boundary
pixels; those raw differences remain visible. There are1832 excluded pixels
across the49 oracle comparisons. No tolerance, boundary radius or assertion was
relaxed. Python integrity replay recomputes all-byte statistics; the unchanged
Scala oracle tests establish which positions are boundary exclusions.

Tests also prove cached scale/pan zero geometry/asset uploads, integer overlap,
old snapshot immutability after further submissions/eviction, bounded inventories
over100 edits/scales, post-GPU cancellation without frame publication, large-target
and precision fallbacks, same-worker retirement/reopen, actual destruction,
stale-generation refusal and zero native owners/frames/tiles/health after close.
Actual packaged canvas pan/zoom/edit/loss/recovery/native-close, ordinary workbench
recovery and ten partial-init/late-callback controls all exit0.
[Commands and exits](measured/invocation.json).

The ordinary workbench's unchanged action/scroll regression diagnostic also
passes: action p95/p9917.726/22.998ms, scroll17.326/17.542ms,100 measured/class
after40 warmups at600x360 logical/1200x720 physical with100 rows.
[Frozen receipt](host-regression/invocation.json), [summary](host-summary.json).
Its package hashes match the camera runs; the diagnostic retains
`qualified=false`. The existing available-host acceptance is not reopened.

**756 distinct Scala cases passed**: required JVM/Native aliases, font/Unicode/
paragraph/render probes and37 final desktop cases. The initial full matrix was
green at755 before the added dense reference test; the scoped final Native GPU
refresh passes34/34, desktop37/37, then the final selective-edit proof refresh
passes7/7. Repeated cases are not added to756. Six existing Python receipt tests
pass;13 examples compile. Three additional receipt controls independently reject
missing samples, geometry drift and a percentile miss.
[Logs](validation/), [negative controls](negative-controls.json).

## Failures retained and scope

The first explicit uniform-layout edit had an incorrect native descriptor array
length and aborted before any test ran. The corrected binding counts/layouts
pass the full GPU checks; [crash and logs](history/) remain. An overlap test first
asserted reuse across different exact scales; it now returns to the same scale
before checking the unchanged overlap requirement. A rebasing experiment exceeded
the original coordinate range on a huge synthetic path; rebasing now checks that
range, and the precision fixture passes through the tiled fallback.

The first packaged dense zoom exceeded16million pairs; failed commands and
partial CSVs remain in [its receipt](history/failed-dense-package/invocation.json).
Exact rebasing alone still refused at16,278,279 pairs/926 edges. Canonical GPU
inputs then removed duplicate builds without raising the cap. A sandbox refusal
of host-load observation occurred before any benchmark launched and is recorded
as `not_run`. Corrected implementation measurements have a new package hash and
directory; earlier failures were never relabeled as passes.

This completes the bounded cached-motion tranche, not arbitrary uncached zoom,
spontaneous hardware-loss safety, other platforms or browser parity. Native
screenshots/visual approval are not inferred from GPU readbacks. The next product
slice is the complete editable workbench: editing/selection, clipboard/undo and
IME. General graphics and spontaneous-loss follow-ups remain open.

```sh
sbt --java-home /path/to/jdk25/Contents/Home testJVM testNative textNative/test probeUnicode/test probeParagraph/test probeRender/test desktopJVM/test examples/compile desktopJVM/desktopPackage
python3 -B desktop/tools/run-camera.py
python3 -B desktop/tools/run-latency.py --diagnostic
python3 -B docs/evidence/camera-retention-20261029/verify.py
```

The evidence verifier checks final source/artifact hashes, saved raw statistics,
frozen sample/geometry/upload counters and both latency protocols. It does not
perform another GPU execution or relabel diagnostics as quiet qualification.
Historical evidence verifiers target their recorded source snapshots; replay
those at their recorded commits.
