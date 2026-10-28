# Curved strokes, gradient glyphs and shared GPU assets

Date: 2026-10-28 (America/Toronto). Mote child
`bd-01M61QEVNY5DMJJ4XQQCGXX9AJ`, under the open general graphics track.
This continues the [completed stroke/gradient tranche](2026-10-28-strokes-gradients.md).
Earlier receipts/readbacks are preserved. The shared checkout remains active;
source hashes identify this slice, not a frozen repository release. Concurrent
desktop/host reservations were respected. No kernel semantics change, external
renderer adoption, native host-gate closure or browser-parity claim is made.

## Curved stroke preparation

Quadratic/cubic strokes now use adaptive **normal ribbons** in path coordinates.
For each accepted Bezier piece, its control hull bounds centerline deviation;
its derivative-control-vector cone bounds normal rotation. The conservative
Frobenius norm of the affine linear part maps those bounds into device space.
The acceptance test is `density * (flatness + width * coneSine) <= tolerance/8`.
Original endpoint tangent directions drive caps and original segment joins.
Adjacent subdivisions share exactly one normalized tangent, preventing rounding
noise from inventing joins. Ribbons split into same-winding triangles, preserving
the one-fill union and translucent overlap semantics, including folded inner
ribbons. Existing cap/join/dash/closed-seam tests remain active.

The sum of each accepted piece's control-polygon-length minus chord-length bounds
the total curve arc-length deficit. Recursive allocation of the length-error
budget keeps that deficit below `tolerance/(16*density)` per complete path, before
dash splitting. Dash endpoint normals interpolate admitted endpoint directions;
normal-cone error still bounds that approximation. Work, points, subdivision
depth, finite arithmetic and local coordinate precision remain explicit limits.
Extreme path-space origins reject when a conservative source-ulp bound consumes
the curve arithmetic error allocation, even if a later target translation would
rebase the output. This avoids claiming precision that was lost before mapping.
GPU operation/storage/sample-work limits remain unchanged; expensive curves can
be refused rather than silently rendered with a coarser approximation.

Stationary interior cusps, and numerically near-stationary cases within the
conservative detection threshold, return an explicit unsupported result for the
whole drawing. No guessed cusp pixels or removed segment is reported as success.
Endpoint-zero derivatives with a defined limiting tangent can be admitted.
Near-reversal miters use a half-angle normal-sum formula instead of cancellation
in `1 + dot(u,v)`. Ill-conditioned admitted miters reject explicitly when their
roundoff bound exceeds the device error allocation. This does not complete all
path/cusp geometry or the general G-1 corpus.

Pure references check narrow and wide quadratic boundaries at 1×/1.25×/1.5×/2×
under shear and reflection, cubic inflection endpoint caps, curve-to-line joins
and miter fallback, closed curved dashes, cusp/depth/precision refusal, and an
independent 10,000-interval Simpson arc-length calculation. Actual GPU curve
pixels use a 32×32 logical quadratic seam and a separate dense nearest-curve
coverage oracle. Samples outside the declared approximation band agree within
one RGBA8 byte; boundary error is checked geometrically. These tiny GPU cases do
not certify dense curves at production viewport sizes.

## Paint-independent glyph coverage

Already-shaped glyph IDs and exact face snapshots are unchanged. Native
preparation now retains grayscale coverage independently of color. Both solid
and linear-gradient glyph paints evaluate in device samples and multiply that
coverage. Gradients stay anchored in canvas/paint coordinates, independently of
the glyph baseline origin, and keep linear-light premultiplied interpolation,
clip and isolated-opacity semantics. Source runs are never reshaped in packing
or painting. Fractional placement and the existing maximum-singular-value raster
density policy are preserved.

An independent scalar FreeType-coverage/gradient/source-over oracle checks real
`office` ligature masks at all four scales, including fractional placement,
translation, clipping, transparent gradient colors and an isolated group. Existing
Latin/rotated-Arabic and glyph-coverage tests also pass. The comparison adapter
still hosts solid masks and explicitly rejects gradient paint with status 14.
ThorVG remains unadopted with its earlier known linear-color failure.

## Shared ownership, bounds and copies

`gpu.compile(prepared)` on the Native probe uses a thread-confined CPU LRU for
decoded linear-premultiplied image pixels and owned glyph coverage. Image keys
use immutable snapshot identity. Glyph keys include exact font-data identity,
face index, logical size, glyph ID, metrics, units/glyph count, service versions
and requested raster density. Raster flags/subpixel phase remain the existing
fixed grayscale/zero-phase settings. Independently resolved faces with identical
inputs share masks; changed sizes and distinct font snapshots do not alias.
Paint, source crop, destination and placement live in per-drawing commands.

Each GPU owner has a fixed-capacity vec4 storage arena. Retained programs pin
stable spans of image/mask pixels. Cache hits acquire those spans without source
marshalling or upload; cold misses upload once. Replay refreshes LRU recency.
Only idle entries can evict; no pinned span moves or is overwritten. Failed
multi-asset admission releases provisional leases and returns no prepared GPU
program. Closing a retained program unpins its assets and drops its CPU program
reference. Idle cache entries intentionally remain owned by the cache. Closing
the GPU retires every program, clears CPU keys/payloads and releases the arena.
No later frame or finalizer is needed to initiate retirement.

Default CPU decoded payload and GPU arena bounds are each **4 MiB / 128 entries**.
The CPU payload count includes decoded vec4 values and retained grayscale masks;
source image/font snapshots, ordinary objects and allocator overhead are excluded.
The GPU distinguishes reserved arena capacity, occupied spans and uploaded bytes.
Program storage/output-texture payload plus reserved arena capacity is admitted
under the existing 64 MiB/64-program limit. Pipelines, the 16-byte dummy binding,
metadata, per-call staging/readback and driver overhead are excluded from that
payload accounting. Source expansion and packed-program limits still apply.
These are explicit payload bounds, not a total process/driver memory measurement.

This is a shared **storage arena**, without production R8 texture atlases,
horizontal subpixel bins, gamma/contrast policy, asynchronous frame queues or a
mounted host cache. The default `PixelProgram.compile` remains self-contained
with inline source pixels, preserving the existing JVM development transport.
Shared ownership is qualified through the Native offscreen owner; desktop files
were left to their reserved workstream.

Copies remain explicit: immutable snapshot import; cold CPU RGBA export/decode
or FreeType mask allocation and vec4 expansion; cold source marshalling and GPU
arena upload; per-new-program command packing/marshalling/upload; then each
replay's mapped staging, native output, Scala array and snapshot copy. Cached
reuse avoids source decode/rasterization/upload, while new commands and readbacks
still have costs. No zero-copy or production throughput claim follows.

## Measured checks and readbacks

The [frozen subset contract](curves-reuse-20261028/contract.json) precedes expanded
validation. Exact input reuse across independently recorded base/overlay/paint
changes produced no additional cache rasterizations or GPU asset uploads at all
four scales. Five repeated replays also leave command-upload counters unchanged.
Shared versus inline readbacks compare byte for byte. Immutable red-to-blue image
replacement preserves old retained pixels. Separate pressure tests force idle
LRU eviction/reupload, hold pinned output stable, refuse all-pinned exhaustion
and prove failed multi-asset attempts release their temporary leases. CPU cache
count/payload limits, owner-thread checks, idempotent close and actual new device
owner/generation refusal/repreparation are tested.

The cumulative all-scale reuse fixture has two immutable 4×4 images and four
unique glyphs at each of four raster densities. At its last scale it reports:

- CPU cache: 18 entries, 133,180 decoded/coverage payload bytes, 16 rasterizations,
  two image decodes; paint/overlay changes reuse those inputs.
- GPU arena: 4,194,304 bytes reserved, 18 entries occupying 125,376 bytes and
  125,376 cumulative asset-upload bytes, without pressure evictions in this fixture.
- Each new overlay/gradient command program uploads 1,248 bytes. Replaying a
  retained program uploads neither commands nor assets.

[Raw counters](curves-reuse-20261028/reuse.txt) retain every scale. The separate
512-byte/two-entry pressure case forces eviction and reupload, so the absence of
eviction above is not represented as eviction qualification by itself. The
inline pixel oracle is outside cache-upload measurements; its source pixels
travel through the ordinary command-storage upload counter. No GPU timestamps,
allocations, RSS/driver memory, display intervals or 120/600-frame production
benchmark were measured. Native live-owner count returns to zero; that is not a
proof of all native/driver leaks or device-loss/timeout recovery.

Twelve lossless PNG readbacks cover the curve, gradient text and shared
base/overlay scenes at all four scales. Representative 2× outputs were visually
inspected for actual content. This is agent inspection of bounded seams, not
human visual approval or a browser differential corpus.

## Verification and retained failures

The full required aliases and native component command passed with **673 distinct
test cases**, zero failed and zero ignored (reruns are not added to this count):

| Suite | JVM | Native |
|---|---:|---:|
| graphics | 63 | 63 |
| core | 9 | 9 |
| textUI | 2 | 2 |
| graphicsUI | 6 | 6 |
| engine | 174 | 174 |
| testkit | 30 | 30 |
| workbench | 7 | 7 |
| host | 6 | 6 |
| textNative | — | 7 |
| probeUnicode | — | 12 |
| probeParagraph | — | 40 |
| probeRender | — | 20 |

```sh
sbt 'project testkitNative' 'set Test / parallelExecution := false' \
  'project root' testJVM testNative textNative/test probeUnicode/test \
  probeParagraph/test probeRender/test examples/compile
```

Native testkit execution is serialized as in prior receipts, with all property
counts/assertions retained. Example compilation passed. Final review tightened
near-reversal miter arithmetic/admission and extreme curve-coordinate admission; the final affected JVM/Native graphics
suites and all 20 GPU cases passed again afterward. The wider matrix precedes
that last local precision refinement in an actively edited workspace.

Retained failures include an initial tuple-append compile error, a WGSL reserved
identifier and wide-curve reference failures. Review unified shared subdivision
tangents and stabilized miters; the persistent reference failure was traced to
the new oracle's absolute ray-division rounding discrepancy. An 80-digit exact-Double
arithmetic check returns winding 1 where division returned 0; relative orientation
returns 1. Its [geometry and reproduction](curves-reuse-20261028/verify-oracle.py)
are preserved. The analytic probe positions, tolerances and expected membership
were unchanged. A first full run then timed out the Native curve oracle after
30 seconds; removing its per-edge collection allocations retained every probe,
assertion and the same timeout. The unchanged Native curve suite subsequently
completed in under nine seconds. No test was weakened or replaced by a stub.

Strict C syntax checking with `-std=c11 -Wall -Wextra -Werror`, source/receipt
hash checks, local documentation links, JSON parsing, exact-oracle reproduction
and lossless PNG/raw-byte verification passed. Pinned native dependency binaries
and headers match the previous receipt; the test executable is newly built.
The [manifest](curves-reuse-20261028/manifest.json) records hashes, logs, commands,
platform metadata, counters and pixel digests.

Next: broader cusp/path and paint coverage, production texture atlases and CPU
geometry/GPU batching, then measured host integration and the full G-1/G-2
reference/performance corpus. General browser-level qualification and the backend
exit decision remain open.
