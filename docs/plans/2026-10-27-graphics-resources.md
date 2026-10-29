# Graphics resources, shared text and preparation boundary

Date: 2026-10-27. Authority: VISION §5–7 and
[the general graphics plan](2026-10-27-graphics-api.md). This is the second
standalone graphics slice. It specifies resource boundaries and the comparison
protocol; it does not adopt a renderer or claim text/native interoperability.

## Implemented image snapshots and dependency inspection

`ImageSnapshot` owns tightly packed RGBA8, tagged sRGB, straight-alpha pixels.
`copyRgba8` validates dimensions and exact byte length, then copies the pixels.
`copyRgba8Rows` validates source stride/extent before allocation and copies only
pixel rows, omitting padding/trailing storage. Dimensions and offsets are checked
with Long intermediates before array indexing. Empty/oversize images are rejected.

There is no caller-minted asset ID or mutable revision slot. Each object denotes
one immutable snapshot. Reimporting changed data produces a new object; old
recordings retain the old pixels. Equal bytes imported twice remain distinct
cache identities. Content-addressed deduplication is not promised. Source arrays
are never retained; the caller keeps them stable during the synchronous copy.
Exports also copy. No decoder, color conversion,
GPU upload, native memory or OS image handle is involved in this slice.

`ImageRegion` retains a snapshot and a positive, in-bounds pixel-edge crop;
fractional crops are allowed. The destination is in the recording's local space,
with no extra DPI conversion. Image opacity is per draw, before enclosing group
composition; it does not replace isolated layer opacity. Nearest and linear
sampling are recorded explicitly. Out-of-source clipping is not implicit: an
invalid crop is rejected. Empty destinations/zero opacity remain recordable.

For rendering, convert sRGB to the working linear space, premultiply, then filter
in that representation. Straight-alpha texture interpolation followed by a late
premultiply can create fringes. A backend must use suitable prepared pixels or
equivalent sampling; it cannot claim correct output from byte upload alone.
Retain original straight-alpha bytes for replay/export; transformed/prepared
copies are backend resources with separate accounting.

`DrawingResources.inspect` returns declared snapshots in first-encounter order.
It visits each shared drawing once, deduplicates snapshots by identity and counts
unique retained pixel payload bytes. The returned inventory strongly retains its
snapshots. It is neither a borrowed view nor a lifetime manager. Traversal is
iterative and bounded by explicit unique-drawing/command limits. Exceeding a limit
returns an error rather than a partial resource set. Every command variant is
matched explicitly so a new variant requires an inspection decision.

These counts describe the unique recording graph. They exclude command/object
overhead and do not measure expanded draw placements, visible pixels, uploads,
GPU allocations or frame cost. Opacity/clipping do not prune declared resources.
A shared recording may be cheap to inspect but expensive to render repeatedly.

## Shared text contract (wrapping and editing geometry implemented; UI integration open)

The separate [`textNative` service](../../text-native/README.md) now provides
explicit static-face opening, HarfBuzz run shaping and FreeType grayscale masks.
Standalone graphics owns immutable `FontData`, `ResolvedFont`, `TextSource` and
`ShapedRun` data, exact glyph-run recording and font dependency inspection. This
is a real run component of the contract below, not a completed paragraph layout.
No alternative UI layout engine or synthetic font/glyph placeholder is introduced.

Standalone `UnicodeAnalysis` now supplies typed source/grapheme mappings,
word/line boundaries, word categories and raw byte-indexed bidi levels with
paragraph ranges. The isolated [Unicode probe](../../probes/unicode/README.md)
provides the real ICU4X analysis service, including a small C ABI adapter to
pinned upstream Unicode 18 algorithms unavailable in the generated C API.
It is not linked into the host or the font service. A subsequent `BidiLine`
component applies upstream line-dependent L1/L2, retaining source-global
codepoint ranges, X9 omissions, base levels and visual source maps. It does not
choose wrap points, reorder glyphs, apply combining/mirroring rules or supply
carets. Those consumers are supplied by the later paragraph components below.
The Unicode/engine labels are explicit; reference evidence is recorded separately.

The isolated [paragraph component](../../probes/paragraph/README.md) now joins
those services with grapheme-aligned script/bidi itemization, contextual shaping
and explicit supplied-face fallback. Its standalone immutable `ParagraphLayout`
retains source mappings, exact resolved faces/glyph runs, visual run placements,
baselines, advance/ink bounds and missing-text diagnostics. Measurement and
recording read the same result. It does not implement the full table below.
The precise whole-item/whole-atom fallback policy and budgets are documented in
the component contract; no OS font-discovery policy or epoch is introduced.

`NativeParagraph.wrap` now uses the same `TextLineLayout` and line engine as
unwrapped layout. Candidate ends are Unicode opportunities intersected with
grapheme boundaries. Actual candidate lines are reshaped within explicit line
context windows; paragraph bidi/script context is preserved and L1/L2 applied
per line. The furthest fitting legal end wins; an unbreakable span overflows
explicitly. All candidates share work budgets. `WrappedParagraphLayout` retains
source-global line ranges, placements, exact metrics/recording and coverage
issues. No trimming, emergency breaking or hyphenation is inferred. See
[the wrapping receipt](../evidence/graphics/2026-10-27-wrapping.md).

The same results now expose lazy `TextGeometry`: grapheme-safe caret stops,
logical upstream/downstream affinity across bidi and wrap boundaries, nearest-
stop hits with explicit alternatives, and unions of visual selection regions.
It derives from stored clusters/advances and line placements, including missing
text and zero-width controls. Usable bounded font GDEF ligature positions are
placed with the shaped glyph origin. Other interiors divide the actual cluster
advance and are explicitly tagged `Interpolated`; these are not measured font
caret positions. Queries do not reshape or require open native owners. Invalid
caret endpoints return None; invalid selections reject without snapping. See
[the geometry contract](../../probes/paragraph/README.md#editing-geometry) and
[its receipt](../evidence/graphics/2026-10-27-editing-geometry.md).

The existing core `TextLayout`/`TextServices` remain provisional. Do not create a
graphics-only independent layout engine. The standalone `ParagraphLayout` is the
shared result's nucleus; its editing geometry and subsequent UI API adapt it. Native
shaping services may depend on graphics/text data; the standalone data model must
not depend on a mounted tree or a UI `Committed` capability. Host calls still
obey the existing phase/transaction rules. The core adapter uses explicit UTF-16
positions with logical affinity; hits retain every alternative and selections
retain line/interpolation metadata. Invalid carets return None and selections
reject without snapping. A pure `text-ui` module depends on both core and
graphics, explicitly converting units and source offsets while retaining one
resolved paragraph. Neither foundational module gains a dependency on the other.
The isolated native paragraph service implements `TextServices` through an
explicit caller-supplied style/font map, preserving `Committed` and existing
native owners. It is not mounted phase dispatch or OS font resolution.
The pure `graphics-ui` recording bridge now supplies core Canvas operations.
Its bounded immutable `PreparedText` inventory is prepared explicitly before
paint or associated with actual resolved results. Source/width mismatch and
conflicting aliases reject; font accounting includes all requested fallback
snapshots. Canvas text services only look up those results. Missing requests
never trigger native shaping during recording. Its lexical/thread-confined
scopes delegate rollback to graphics, and closing clears theme/inventory
references. See [the Canvas contract](../../graphics-ui/README.md) and
[its receipt](../evidence/graphics/2026-10-28-canvas-ui.md).
The implemented glyph-run operation retains actual
resolved positions and face bytes rather than a family string or invented IDs.

The shared result must carry enough information for all its consumers:

| Data | Required meaning |
|---|---|
| Resolved faces | Actual immutable font bytes or explicit owner-backed font resources; collection face index, variation coordinates and synthetic style choices; reproducible identity for those exact inputs |
| Shaped runs | Direction/script/language, shaping features, resolved face, glyph IDs, advances and offsets, source clusters and visual ordering; units/size and shaping-version metadata |
| Placement | Positioned runs, baselines, line metrics, ink and advance bounds, wrapping/alignment decisions; explicit local-to-layout mapping |
| Logical geometry | Original text and typed UTF-8/UTF-16/grapheme mapping; line/cluster/caret topology and selection regions, including bidi and combining sequences |
| Dependencies | Font-resolution/metrics epoch and all inputs that affected layout; retained face identity for replay rather than a fresh family-name lookup during paint |

Measurement, painting, caret/hit/selection and accessible geometry read this one
result. A placed glyph run alone does not define logical caret behavior. Painting
does not reshape or independently substitute fonts. Measurement cannot use one
fallback face while painting uses another. Downstream adapters use the same
font-selection/shaping decisions for their metrics and drawing interfaces.

Font discovery epochs invalidate unresolved font/layout requests. An already
resolved immutable layout retains its exact face dependencies; it must not
silently rebind after font installation/removal. If hinted metrics or ppem affect
layout, their scale/flags are layout inputs and a replacement result is published
coherently to every consumer. If scale affects rasterization only, invalidate
glyph preparation without unnecessarily reshaping the paragraph.

Native font objects live under explicit host owners, not finalizer cleanup.
Borrowed native shaping output is copied into owned immutable data before the
borrow ends. A recorded layout must either retain reproducible font inputs or
declare a real lease/availability dependency; a bare numeric font ID is
insufficient. Retiring an owner forbids new use immediately and releases backend
resources or queues their retirement until submitted work completes, as required
by §5. Existing
callback, thread and blocking-extern rules govern those services.

Acceptance needs actual pinned fonts, multilingual shaping/fallback/variation,
ligatures/combining/bidi, rotated labels, ink/advance bounds and caret/selection
agreement. A fixed character-width estimator or invented glyph fixture cannot
certify this contract.

## Backend preparation and ownership (bounded probe implemented; production open)

Recordings remain device-independent. Preparation accepts a recording, its
complete resource inventory, the admitted target description and backend
capabilities. It returns prepared resources or an explicit unsupported/failed
result; it must not omit commands or silently substitute a lower-fidelity mode.
`DrawingPreparation.prepare` now executes the bounded subset described in the
[offscreen receipt](../evidence/graphics/2026-10-28-offscreen.md), with typed
unsupported/budget/invalid errors. It retains exact dependencies and target
identity; it is not a public native handle or successful renderer stub.
The continuation adds explicit probe-owned retained programs with finite inventory
bounds, source-upload counters and synchronous replay/retirement. Program storage,
output textures/views and bind groups persist; readback/staging remain per call.
The next bounded slice separates immutable image/mask payloads from per-drawing
programs. A confined decoded/raster LRU and fixed-capacity shared GPU vec4 arena
reuse those payloads by snapshot/font/raster identity. Programs pin stable spans;
only idle spans can evict. CPU and GPU payload/count bounds are independent,
and allocated arena capacity is charged to the device budget. Glyph paint is
sampled independently of mask coverage. This is a Native qualification resource
store; production texture atlases and host caching remain open. Replacement requires new preparation and retention. Closing a program
clears CPU dependency references; closing the GPU retires every child. See
[the continuation receipt](../evidence/graphics/2026-10-28-strokes-gradients.md).

The bounded triangle/texture continuation uses a separate `BatchGpu` owner.
Translation-normalized CPU meshes share pinned stable GPU spans; ordered index
and paint records batch consecutive draws without reordering. R8 glyph cells and
linear-premultiplied RGBA32Float image cells replace vec4 source buffers for this
path. Per-source clamping prevents atlas bleed. Fixed cell sizes, a two-layer
limit and a 640x360 device-target limit reject explicitly. Independent CPU cache,
GPU capacity and per-program payload counters retain their distinct meanings.
See the [frozen batch contract](../evidence/graphics/batch-20261028/contract.json).
Mounted host integration and the full G-1 corpus remain separate gates.

The target description must identify the backend build, actual device identity
and non-reused device generation, target color/alpha/format, logical-to-device
mapping, and chosen quality/error tolerances. Scale alone is insufficient for
curves under shear/nonuniform transforms. Preparation keys include every input
actually used, and separate reusable CPU geometry from device-owned objects.

| Change | Required action |
|---|---|
| Recording or snapshot replacement | Prepare the new dependency; old recordings remain immutable. Reuse only unchanged inputs. |
| Font-resolution/metrics change | Invalidate affected unresolved layout requests; retain exact resolved dependencies until explicitly replaced. |
| Scale/transform/tolerance change | Recheck device-space approximation and glyph rasterization; retain CPU resources whose error bound still holds. |
| Target format/color policy change | Rebuild format/color-dependent resources/pipelines; do not silently reinterpret cached pixels. |
| Device loss/recreation | Invalidate every resource belonging to the retired generation, even on the same adapter; reprepare from retained inputs. |
| Owner disposal | Forbid new use immediately; release CPU leases and queue/defer native retirement as required by submitted work. No later frame or GC is required to initiate cleanup. |

A host scope owns prepared resources and bounds caches. Cache eviction and
submission fences determine physical reuse; they do not extend logical owner
validity. No kernel scheduling, transaction, identity or lifetime policy changes
are introduced by these rules. The eventual renderer must implement them through
the existing host/native boundary rather than adding a parallel runtime.

Name each copy: snapshot construction, export/marshalling, transformed pixel
preparation, staging and GPU upload. `imageBytes` is retained source payload, not
zero-copy evidence. A backend can later introduce a constrained native borrow
without changing snapshot semantics, but must separately establish reachability,
nonmutation, span and synchronization. Snapshot updates currently copy complete
images; streaming/partial-update views require a later explicit revision and
ownership contract rather than mutating a recorded snapshot.

## Bounded wgpu/ThorVG comparison protocol

This protocol is assigned to G-0/P0.3/G-2, under existing native feasibility and
pause gates. It compares one direct renderer slice with one ThorVG probe; it does
not authorize multiple production backends or source builds outside artifact
policy. Pin the engine/shim/header/compiler versions and artifact hashes before
running. Pin fonts, scene inputs, adapter, OS, target format and color settings.
Use the same device where compatible sharing is demonstrated; otherwise report
the incompatible path as a finding, not shared-device success.

Start with the following bounded corpus at a 1280×720 logical viewport, at
1×/1.25×/1.5×/2× scale. Exact coordinates and reference images are frozen as
fixtures before benchmarking; this protocol alone is not the G-1 fixture gate.

| Scene | Initial size and purpose |
|---|---|
| Widget drawing | 1000 rounded rectangles/borders with transparent overlaps; specialized versus general paths |
| Curved geometry | 1000 cubic paths, thin strokes, joins/dashes, holes/self-intersections; approximation correctness |
| Paint/clip/layer | 32 nested clip levels, gradients/patterns under transforms, 2 nested isolated-opacity groups; separate backdrop-dependent cases |
| Ordered instances | 10000 mixed/translucent marks, constant and varying paint columns; order and batching |
| Images | 256×256 source updated at 60 revisions/s, cropped/fractional placement, 2-pixel transparent-edge references; upload/sampling |
| Text | 100 multilingual labels using pinned resolved layouts, rotated labels and fallback; shared measurement/painting |
| Reuse and lifetime | Reused drawing plus hover overlay, 20 resize/scale transitions, 100 mount/dispose cycles, forced device-generation replacement |

Unimplemented operations remain explicit unsupported/blocked cells. Subset results
do not certify complete scenes. First establish the actual device/texture bridge,
queue/submission order, resource retention, synchronized teardown and positioned
glyph integration. If a required seam is unavailable, record it and stop that
path rather than adding hidden readbacks, independently reshaped text or a second
unaccounted GPU stack to produce a favorable benchmark.

For working scenes, collect 10 cold preparations, 120 warmup frames and 600
measured frames. Separate initial preparation, unchanged replay, hover overlay,
pan/zoom/resize and image replacement. Record CPU/GPU p50/p95/p99, input-to-display
latency, allocations, source/prepared/retired memory, staging/upload bytes and
missed display intervals. Frame intervals are 16.67 ms at 60 Hz and 8.33 ms at
120 Hz; these are targets, not measured performance or a fixed per-backend GPU
budget. Before tuning, the G-1 fixture receipt must set numeric per-scene
CPU/GPU/memory/transfer budgets on the pinned host and record pixel tolerances.
Do not retrospectively change those budgets to admit a result.

Compare correctness/coverage first, then measured costs and engineering burden.
Record supported/unsupported operations, artifact packaging, required bridges,
copy paths and responsibility changes as well as frame timings. G-2 can choose
direct wgpu after a failed/blocked ThorVG probe with evidence; it does not need a
second renderer to pass every operation. External adoption still requires the
explicit VISION responsibility update. The general graphics contract remains
the acceptance target.

## Scope and next implementation

The resource slice implements image recording and resource inspection; the next
components implement real static-font run shaping/masks, isolated Unicode
analysis and shared wrapped/unwrapped paragraph integration as described above.
Full text/layout, production prepared resources, general GPU interoperability,
output fidelity and performance remain specified or unqualified. A first
bounded preparation/offscreen/ThorVG seam now has
[executable evidence](../evidence/graphics/2026-10-28-offscreen.md). The vector and image-viewer
specimens advance the public-only corpus; the remaining general consumers stay
open. Shared logical editing geometry and CPU preparation are now implemented.
Next renderer work expands cusp/paint coverage and the broader G-1 corpus,
adds texture atlas/production resource preparation, and joins the mounted host through
the existing native gates. ThorVG remains unadopted: the tested configuration's
color mismatch, BGRA target requirement and sampling/crop limits are recorded,
not waived. No full G-2 exit or browser-quality qualification is claimed.

The bounded triangle/texture tranche now has
[measured four-scale evidence](../evidence/graphics/2026-10-28-batch.md), including
full 320x180 corpus pixel comparisons and cold/warm/resource budgets. Production
packing, mounted host acceptance and the full G-1/G-2 exit remain open.
