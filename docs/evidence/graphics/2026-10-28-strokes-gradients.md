# Polyline strokes, linear gradients and retained offscreen programs

Date: 2026-10-28 (America/Toronto). Mote child
`bd-01M61FDV50EC3BYQREMYWT5ZXK`, under the open general graphics track.
This continues the [completed offscreen tranche](2026-10-28-offscreen.md).
The existing receipt/readbacks are preserved. The checkout is concurrently
edited; this receipt qualifies the named source hashes, not a frozen whole-tree
release. No kernel semantics or external-renderer adoption changes.

## Implemented scope

`DrawingPreparation` now expands **polyline** strokes in path coordinates:
butt/round/square caps, bevel/round/miter joins with limit fallback, dash phase,
odd normalized patterns, zero-length dash entries, multiple subpaths and closed
dash seams. Widths and dash distances transform with the path. Same-winding
polygons form a single nonzero union fill, so overlap at joins and self crossings
does not apply translucent paint multiple times. Round polygons use a conservative
affine norm to bound device-space chord error to half the target tolerance.
Explicit work/point limits reject before unbounded expansion. Underflow,
nonfinite arithmetic and inadequate local coordinate precision reject.

**Curved strokes remain unsupported.** Fill-curve flattening alone does not
qualify normal/offset/miter error for arbitrary-width curves and cusps. The
whole preparation rejects these strokes, including inside zero-opacity groups;
there is no curve-shaped successful placeholder. Gradient glyph paints also
remain unsupported. These are real remaining capability cells, not a G-1 exit.

Linear gradients now work for fills, polyline strokes and ordered instances.
CPU preparation composes paint/local/device transforms and creates a path-relative
Double projection. Translating an entire prepared path preserves its brush,
including the concurrent desktop tiler's existing geometry translation. Packing
checks coefficient-packing precision at target corners (absolute parameter error
<=1e-6), with projected parameter extrema bounded to ±65,536. Packed edges are
bounded to ±1,048,576 device units to keep orientation products finite. An
extreme repeated-gradient regression rejects before float projection overflow.
WGSL interpolates **linear-light premultiplied** stop colors, preserves supplied
coincident-stop order and implements clamp/repeat/reflect. Nearest endpoint stops
extend to 0/1 before extension. Transparent green at the end of an opaque-blue
ramp contributes no green fringe. Singular paint/device mappings reject.
Each gradient is limited to 4,096 stops; stop traversal counts against the
existing 500-million sample-work bound and packed storage budget.

The C comparison adapter rejects gradient fills with status 14. ThorVG remains
unadopted with its earlier recorded color mismatch; no gradient conformance or
performance comparison through that adapter is claimed.

## Explicit retained resources

`gpu.retain(program)` uploads the immutable packed program once and owns its
storage buffer, output texture/view and bind group. `RetainedProgram.render()`
reuses those objects. It does not recompile, reshape, rasterize glyphs, marshal
source words or upload them again. Original exact image/font dependencies stay
retained until explicit close or parent disposal. The complete program is the
reuse unit: there is no cross-program image/glyph atlas, deduplication, eviction
cache or production-host integration in this slice.

Owner confinement and actual device-generation identity apply before any native
use. A closed program cannot replay; closing either a program or its GPU retires
its resources and clears its CPU program reference without requiring GC or a
later frame. Native calls remain pointer-only and potentially blocking calls
are inventoried in VISION §5.4. Foreign callbacks execute no Scala.

Admission bounds each device to **64 retained programs and 64 MiB** of requested
storage-buffer plus output-texture payload. Counters name that payload and direct
storage upload bytes. They exclude driver/allocator overhead, pipelines,
bind-group metadata and staging/readback allocations. The copy path still has
CPU linear-pixel expansion, initial Scala-to-native marshalling/storage upload,
then every replay's GPU-to-mapped staging, native output, Scala array and
immutable snapshot copy. This is not zero-copy rendering.

## Pixel and resource checks

The [subset contract](strokes-gradients-20261028/contract.json) froze scope,
scales, tolerances and resource bounds before expanded validation. Pure analytic
checks cover transformed cap extents, round chord error under shear/reflection,
miter versus bevel/round behavior and limit fallback, dash phase/odd/zero entries,
closed seams, degenerate paths, rejection budgets and paint coordinate mapping.

Actual Metal/wgpu execution uses the same pinned stack and Apple M2 Pro as the
previous receipt, at **1×, 1.25×, 1.5× and 2×**. Pixel tests compare analytic 4×4
coverage for caps/dashes and an independent scalar gradient oracle, within one
RGBA8 byte. Round boundary pixels inside the admitted geometric approximation
band are checked geometrically rather than asserted to be exact circles.
Nested clips, isolated opacity, gradient strokes and transformed instances share
the same paint semantics. This is independent analytic/scalar evidence, not a
browser differential corpus or human visual approval.

Retained checks compare replay byte for byte, measure zero further direct
source uploads over 20 replays, exercise 20 resize/reprepare cycles, enforce
both inventory limits and retire resources on close. Exact already-shaped
`office` masks and immutable red-to-blue snapshot replacement are checked at all
four scales. Old retained programs keep their old pixels. Native live owners
return to zero; this is not a driver-memory/RSS leak proof or device-loss test.

Lossless readbacks and diagnostic counters are in
[the receipt directory](strokes-gradients-20261028/). Agent visual inspection
checks actual scene content, with no general visual-quality acceptance claim.

## Verification

Both required full platform aliases passed, including the concurrent host
suite; its previous `CommittedTree.scala:51` compile blocker is gone. The matrix
contains **659 distinct test cases** across these suites (not a sum of reruns):

| Suite | JVM | Native |
|---|---:|---:|
| graphics | 59 | 59 |
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
| probeRender | — | 14 |

No test was ignored or replaced by a successful stub. Native testkit execution
was serialized, preserving the existing property counts and assertions, as in
the prior receipt. The full command ran both root aliases and all native
service probes. It passed both platform aliases and the service suites, then
failed the new GPU tile-equivalence assertion. Its log is retained.

That regression exposed division/absolute-intersection rounding in the existing
ray test: one coverage sample at a transformed boundary changed under integer
tile translation. The shader now tests relative edge orientation without division.
The **unchanged** all-pixel tile oracle passes at all four scales within the
original one-byte tolerance. This improves the reference rasterizer and does not
claim general exact-predicate geometry. No pixel tolerance was widened.

Final review also corrected square zero-length dashes to follow the segment
tangent and added a diagonal regression. Both graphics platform suites passed
again after that correction. All 14 actual GPU cases passed after the shader
fix, including retained glyph/snapshot replacement and the tile oracle. Example
compilation passed. A final GPU run also covers pre-submission rejection of an
extreme gradient that would overflow shader projection arithmetic.

The successful platform and service commands are recorded in the
[manifest](strokes-gradients-20261028/manifest.json). Earlier compile failures
(undefined helper variable, mutable-local warnings and a local forward reference),
an initially incorrect contour-index fixture, the initial tile discrepancy and
a WGSL parsing error during its correction are retained. The shader parser
failure caused real failures, not skipped or substituted pixel results. The
initial sandbox denied the sbt cache lock; the subsequent cache-enabled runs
executed normally.

Strict C shim syntax checking (`clang -std=c11 -Wall -Wextra -Werror`),
`git diff --check`, contract/manifest JSON parsing and lossless PNG byte
verification passed. The pinned native dependency binaries/headers match the
prior receipt; only the newly built test executable changes. Source and
artifact hashes identify the final scoped state. Representative 2× stroke,
reflected-gradient and retained-glyph readbacks were visually inspected.
The final graphics/GPU reruns cover the final affected code; the wider platform
matrix precedes those last local fixes in a concurrently edited workspace.
This is not a frozen release candidate or the full G-1 performance protocol.

Next: qualified curved-stroke offsets/cusps and their references, glyph-gradient
coverage and broader paints, shared image/glyph atlas/resource reuse, then
production path preparation and host composition under the existing gates.
The full G-1 corpus, G-2 backend decision and browser-quality qualification
remain open.
