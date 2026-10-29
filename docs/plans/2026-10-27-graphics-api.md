# Public graphics API: capability and quality plan

Date: 2026-10-27. Design authority: VISION §7.1–7.2.

The owner requires a graphics API every bit as good as browser graphics.
This plan makes that a product requirement with evidence gates. It does not
claim an implemented renderer or freeze Scala signatures. The existing `Canvas`
is an authoring skeleton; the headless kernel and native feasibility work retain
their current scope and prerequisites.

## Implementation checkpoint: vector recording foundation

The first implementation is the independent `graphics` cross-project,
`sirin-graphics`, on JVM and Scala Native. It has no UI/core, engine or native
library dependency. [The module contract](../../graphics/README.md) specifies
the provisional API, coordinate/state semantics and ownership of immutable values.
Existing UI geometry remains unchanged; a later bridge converts it explicitly.

Implemented: Double geometry and affine operations; immutable line/quadratic/cubic
paths and rectangle construction; solid/linear-gradient paints and stroke styles;
ordered fills/strokes/instances; reusable child drawings; scoped transforms,
path clips and isolated-opacity command boundaries; failure rollback and lexical
recorder/builder validation. The command representation is a flat immutable
vector with shared geometry/paint/batch/drawing references. No compact-packing
or runtime performance claim is made. A public-only diagram specimen compiles
without mounting widgets.

The second slice adds owned immutable RGBA8 snapshots, source cropping/sampling/
opacity commands, a public-only image-viewer specimen, and bounded transitive
resource inspection. [The resource contract](2026-10-27-graphics-resources.md)
specifies shared text, backend preparation/invalidation and the bounded comparison
protocol. Complete text/layout services and prepared resources remain open. See
[the resource-slice evidence](../evidence/graphics/2026-10-27-resources.md) for
verification, copy accounting and limitations.

The third slice adds owned font/source/glyph-run data, exact run recording and
font dependency inspection, plus the separate
[`textNative` service](../../text-native/README.md). Checksum-pinned HarfBuzz and
FreeType shape explicit static Latin/Greek/Arabic fixture runs and produce owned
grayscale masks on macOS arm64. It has no automatic fallback, paragraph bidi/line
layout, caret/selection topology, UI adapter or GPU atlas. Its artifact-dependent
tests run explicitly and in a dedicated macOS CI job, outside the dependency-free
root suites. See [the text-component evidence](../evidence/graphics/2026-10-27-text-native.md).

The next component adds standalone Unicode-analysis data and an isolated
[`probeUnicode`](../../probes/unicode/README.md): ICU4X segmentation and raw bidi
levels through a pointer-only shim, with a pinned Rust C ABI adapter for Unicode
18 grapheme/line constructors absent from the generated C API. This is a shared
text-layout prerequisite, not paragraph layout, fallback or UI editing geometry.
See [the Unicode receipt](../evidence/graphics/2026-10-27-unicode.md) for reference
qualification, size measurements and remaining P0.8 work. Production co-linking
with wgpu/AccessKit still requires the unified `sirin-native` library.

`BidiLine` extends that component with source-preserving line-adjusted levels
and visual codepoint maps. It keeps paragraph context when processing a wrapped
line, exposes X9 omissions and actual base levels, and does not invent positioned
glyphs or editing geometry. See [line-bidi evidence](../evidence/graphics/2026-10-27-bidi-lines.md).

The [paragraph component](../../probes/paragraph/README.md) joins those services
in an isolated process with one Rust library. It adds Unicode-property-backed
script itemization, contextual logical-range shaping, explicit supplied-face
fallback and shared unwrapped paragraph placement/measurement/recording. Missing
text and unsupported variation sequences remain explicit. See
[paragraph evidence](../evidence/graphics/2026-10-27-paragraph.md). That checkpoint
did not qualify OS discovery, wrapping, editing geometry, the core adapter or GPU hosting.

The word-wrapping slice now shares `TextLineLayout` between wrapped and unwrapped
preparation. It preserves paragraph bidi/script context, reshapes every candidate
within its line's context, retains exact source/position mappings and reports
unbreakable overflow. Measurement and recording read the same stored lines.
[Wrapping evidence](../evidence/graphics/2026-10-27-wrapping.md) records real-font
cases and budgets.

The shared lines now provide lazy grapheme-safe editing geometry: explicit
logical affinity for dual bidi/wrap caret positions, nearest-stop hits retaining
ambiguity, and unions of visual selection regions. It uses the stored advances,
source mappings and usable font GDEF caret coordinates; otherwise inferred
interiors divide the actual cluster advance and are tagged `Interpolated`.
It does not reshape or require live native owners. See
[editing-geometry evidence](../evidence/graphics/2026-10-27-editing-geometry.md).
The pure `text-ui` adapter now preserves that topology in core UTF-16/Lp terms,
including affinity and all hit alternatives, while recording the identical
resolved runs. The isolated native paragraph component implements actual
explicit-style `TextServices` through the same pipeline. See
[the adapter evidence](../evidence/graphics/2026-10-28-text-ui.md).
The pure `graphics-ui` bridge now records the current core Canvas interface:
rectangle/rounded-rectangle paths, centered strokes, lines, path clips, offsets,
isolated opacity and exact resolved text. By-name scopes restore/roll back their
active targets. String convenience calls and canvas text services use an explicit
bounded `PreparedText` inventory; missing requests reject without shaping in
paint. Rounded corners are documented cubic approximations. A public Element
test exercises real measurement, selection and painting through this bridge.
See [the Canvas receipt](../evidence/graphics/2026-10-28-canvas-ui.md).
Hard separators/tabs, hyphenation/emergency breaks, mounted phase/worker dispatch,
editing/IME behavior and production GPU hosting remain open.

The next bounded slice implements `DrawingPreparation`: target/device-generation
identity, complete dependency retention, ordered expansion, device-space adaptive
curve flattening and explicit admission/budget failures. An isolated actual
wgpu probe renders the admitted path/image/glyph-mask/clip/opacity subset at the
four required scales. The pinned ThorVG comparison establishes shared-device
texture and positioned-mask seams but exposes a linear-light color mismatch;
it is not adopted. The [offscreen evidence](../evidence/graphics/2026-10-28-offscreen.md)
records pixel oracles, copy paths, format restrictions, timings and open gates.
The bounded continuation adds path-space polyline strokes (caps/joins/dashes),
transformed linear-light gradients and explicit retained GPU program owners.
The following slice admits bounded quadratic/cubic normal ribbons and gradient
glyph masks, then adds a confined decode/raster LRU and pinned shared GPU vec4
arena across independent drawings. Stationary cusps, production texture atlases
and production hosting remain open. See
[the curves/reuse receipt](../evidence/graphics/2026-10-28-curves-reuse.md). See [the continuation receipt](../evidence/graphics/2026-10-28-strokes-gradients.md).

The next implementation slice freezes a 320x180 standalone corpus and numeric
quality/resource/timing budgets in
[the batch contract](../evidence/graphics/batch-20261028/contract.json).
It adds bounded CPU winding/clip tessellation, ordered GPU triangle batches,
translation-normalized shared geometry and pinned R8 glyph/RGBA image texture
cells. The reference interpreter remains the pixel oracle; the new owner is
separate from the concurrently developed host. Measurement and acceptance are
recorded in the tranche receipt. Fixed cells, two isolated layers and explicit
work/target bounds are admission limits, not production/browser qualification.

This advances parts of G-0/G-1 and the G-2 nucleus. No gate or full capability row is complete.
Production rendering/preparation, full text/fallback services, image formats/decoding/streaming updates,
remaining geometry/paint operations,
general authoring corpus, full renderer comparison/backend decision and UI hosting remain open. A
recorded clip or layer establishes command semantics, not correct rendered pixels.
See [the foundation evidence](../evidence/graphics/2026-10-27-recording-foundation.md)
for verification and limitations. Graphics tests are included in `testJVM` and
`testNative`; unavailable backend checks are not represented by passing stubs.

## Reference and scope

Canvas 2D supplies a reference for drawing operations and offscreen/pixel work;
SVG supplies references for vector painting and geometry. The compositing and
filter specifications supply additional semantics. These are references for
capability and observable behavior, not requirements to reproduce JavaScript,
the DOM, CSS parsing, or an SVG document loader.

- [HTML Canvas 2D specification](https://html.spec.whatwg.org/multipage/canvas.html#2dcontext)
- [SVG paths](https://www.w3.org/TR/SVG2/paths.html) and
  [painting](https://www.w3.org/TR/SVG2/painting.html)
- [Compositing and Blending Level 1](https://www.w3.org/TR/compositing-1/)
- [Filter Effects Level 1](https://www.w3.org/TR/filter-effects-1/)

G-1 freezes a dated operation inventory and records reference browser versions,
OS, fonts, scale, GPU adapter, and color configuration. A specification entry is
not proof that every browser implements it. Differences among Chromium, Firefox,
and WebKit must be recorded; one browser's pixels are not the sole oracle.
Newer browser capabilities are reviewed explicitly rather than silently moving
a frozen acceptance target.

## Subsystem and public boundaries

`sirin-graphics` is a first-class, general-purpose subsystem. Its drawing model
and recording API must be usable without mounting a widget tree. Controls,
custom components, diagrams, editors, charts, image viewers, and animated content
are peer consumers. No downstream library defines the public operation set,
module boundaries, or release sequence. Intaglio is a supported independent
consumer, not the organizing model of Sirin graphics.

G-0 specifies three boundaries; names below are provisional:

- **Drawing API:** paths, explicit paints/strokes, images, measured text,
  transforms, clips, compositing groups, and ordered instances. Scoped operations
  restore state even on failure; callers need not balance a save/restore stack.
- **Recorded drawing:** reusable, inspectable commands and logical resource
  references, independent of a GPU device. Shared immutable geometry and compact
  storage avoid a widget per mark or a large per-frame object tree. Define image
  snapshot/version behavior, font identity, and resource ownership explicitly;
  a recording must not hide a borrowed pointer or mutable native scene graph.
- **Prepared drawing:** backend/device-specific geometry, atlases, buffers,
  textures, and caches derived from a recording. Specify invalidation for content,
  fonts, scale, device loss and disposal. Recordings remain replayable subject to
  their declared resource availability. Native handles stay internal.

The public GPU surface API remains available for custom GPU work. The UI host
connects drawings to layout, input, semantics and invalidation; it does not make
mounting a component a prerequisite for recording or offscreen rendering.
Resource owners and host integration follow VISION §5; this separation introduces
no independent scheduler, transaction system, or lifetime policy. G-0 proposes
the dependency/module layout before implementation, without freezing signatures.

## G-0: Rendering design and bounded backend comparison

The baseline follows the original renderer plan:

```text
Controls / custom components / independent library adapters
                         ↓
             Sirin public Scala graphics API
                         ↓
               Reusable recorded drawing
                         ↓
             Backend-specific prepared drawing
                         ↓
      Sirin wgpu renderer / qualified external backend
                         ↓
                  Composited output
```

Sirin owns the public API and drawing semantics regardless of rasterizer.
Direct lowering to wgpu remains the strategic baseline, subject to evidence.
Sirin converts geometry, paints, text and layers into buffers, textures, pipelines,
render passes and draw calls. Paths start with CPU preparation/tessellation;
specialized instancing handles common shapes and repeated marks, glyphs use
atlases, and effects/compositing use intermediate targets where required.
Focused algorithm libraries may be reused without exposing their types publicly.

G-0 specifies GPU mappings, batch/resource representations, copy/ownership
accounting and evidence needed from P0.3 and G-2. It also defines a bounded
comparison with **ThorVG as the first external candidate**. This is a feasibility
comparison, not adoption or a requirement to ship two production renderers.
Public API/recording work proceeds independently; complete the comparison and
record the backend decision by the G-2 exit, before committing to the full G-3
renderer expansion. Existing native prerequisite gates still govern probes.

ThorVG's [C API](https://github.com/thorvg/thorvg/blob/main/src/bindings/capi/thorvg_capi.h)
and [WebGPU integration](https://github.com/thorvg/thorvg/wiki/WebGPU-Engine-Development)
make it a relevant candidate. Target interfaces are not evidence that Sirin's
pinned handles, submission order, text services or resource lifetimes interoperate.
Pin the engine, headers, build options and wgpu version before a native probe.

Compare the same bounded general-purpose corpus through the direct renderer and
ThorVG: widget primitives, curved/dashed paths and holes, transformed patterns,
nested clips and layers, multilingual text, raster updates, and dense ordered
instances. Include independent-library scenes without making them the entire
corpus. Establish budgets and stop criteria before implementation. Report:

- Semantic/visual coverage, unsupported cases, and any required intermediate
  targets or raster fallbacks; no silent approximation of an unsupported effect.
- Shared font resolution/shaping and positioned-glyph integration; measuring with
  Sirin and independently laying out text in a backend is not sufficient.
- Compatible GPU device/texture ownership, submission ordering, synchronization,
  thread/ABI behavior, resize, device loss and teardown.
- Cold preparation versus repeated frames; pan/zoom/resize versus hover; CPU/GPU
  time, allocations, uploads, retained memory, packaging and maintenance costs.

A failed or blocked ThorVG probe is a recorded result, not grounds to weaken the
contract or automatically start another full backend. The decision may retain
wgpu, propose delegation, or identify a specific follow-up. External adoption
requires an explicit VISION §6/§7.2 update allocating rasterization, batching,
atlas and shader responsibilities. Scala retains the public model, application
semantics, composition policy, lifecycle integration and diagnostics. No native
interoperability or rendering performance is established by this plan.

**Optional alternative: Skia.** Its [public API](https://docs.skia.org/docs/user/api/)
and [filter API](https://api.skia.org/classSkImageFilters.html) offer relevant
machinery; SkiaSharp maintains a
[native C wrapper](https://github.com/mono/SkiaSharp/blob/main/documentation/dev/architecture.md).
Evaluation is warranted only by a concrete quality, performance or engineering
cost finding; it does not block the direct wgpu implementation. Adoption would
need evidence for artifact/ABI delivery, GPU integration, text consistency,
lifecycle and costs, plus an explicit revision of rendering responsibilities.
In particular, Skia's
[Dawn backend](https://github.com/google/skia/blob/main/include/gpu/graphite/dawn/DawnBackendContext.h)
does not establish interoperability with wgpu-native handles.

Vello remains architectural reference material and a possible focused experiment.
A CPU raster backend may serve bootstrap, headless, fallback or differential
checks, with changed-frame rasterization/upload costs measured. Neither is a
committed production backend. Skia evaluation remains conditional on findings;
this workstream does not implement five engines.

## Cross-backend contracts

- **One meaning:** controls and custom graphics share drawing semantics even
  when specialized internal paths differ. Do not default to an external canvas
  per widget. Rendering a subtree against transparency and compositing its
  texture cannot generally reproduce backdrop-dependent blending; isolation,
  backdrop access and legal flattening must be explicit.
- **Text:** font resolution, shaping, metrics and painting use the same decisions
  and reusable layout result. Define resolved font identity, fallback and font
  revision invalidation, not just a family-name string. Adapters translate units
  at their boundary and reuse Sirin text services for measurement and drawing.
- **Coordinates and precision:** retain Double geometry through CPU preparation;
  define the supported numeric domain, clipping and local-origin rebasing before
  GPU float conversion. Define curve/stroke approximation error in device space,
  and invalidate scale-dependent preparation when it exceeds the tolerance.
  Map logical coordinates to backing pixels once, including fractional DPI.
- **Ordering:** instance batches preserve painter order, clips and blending;
  regrouping by shape/paint requires proof it cannot change overlapping output.
- **Interaction:** hosts map input through corresponding inverse transforms and
  can invalidate overlays separately from the underlying drawing. Geometry
  queries are public; domain picking stays with the consumer where appropriate.
  Semantic representations are deliberate and need not create one accessibility
  node per mark. Drawings do not imply a second UI tree.

## Capability matrix

All rows are currently **unqualified**; some recording/value operations are
implemented as described in the checkpoint above. G-1 expands each row into
operation-level cases and maps each to a public API and an evidence receipt.
Every row is required for full graphics qualification; the nucleus is a subset.

| Family | Required public capability | Qualification cases |
|---|---|---|
| Geometry | Immutable reusable paths; segments, quadratic/cubic curves, arcs, ellipses, rounded shapes, compound paths; nonzero/even-odd fill; bounds, containment and stroke hit queries | Holes, self intersections, degenerate segments, extreme zoom, thin curves; geometry checked against independent analytic references |
| Stroke and paint | Caps, joins, miter limits, dash arrays and phase; solid colors, linear/radial/conic gradients, transformed vector/raster patterns; explicit paint coordinates and stop interpolation | Closed dash seams, joins under scale, coincident stops, transparent colors, pattern alignment across clips |
| Coordinate state | Arbitrary affine transforms, composition and inverse mapping; nested path clipping, masks, scoped state restoration | Rotated clips, reflected paths, singular transforms, nested state and exception-safe restoration |
| Text | Font selection/fallback, shaping, bidi, combining marks, ligatures, color glyphs, baseline/alignment, fill and stroke text; reusable measured layout with ink/advance bounds | Pinned multilingual fonts; drawing, picking and accessible geometry agree; font and scale changes invalidate the right work |
| Raster and pixels | Source cropping, scaling, nearest/smooth sampling, explicit image formats and color profiles, CPU pixel updates, readback and bitmap export | Transparent edges, subpixel placement, sampling at multiple scales, upload/readback bytes and ownership; transfer cost is explicit |
| Compositing | Porter–Duff operators, separable/nonseparable blend modes, isolated group opacity, offscreen layers and masks | Independent numerical alpha/blend oracles; overlapping children, nested isolation, transparent backdrops, transformed masks |
| Effects | Shadows; ordered basic filters (blur, brightness, contrast, grayscale, hue rotation, invert, opacity, saturation, sepia, drop shadow); graph composition for SVG-style filter primitives | Effect bounds, clipping, padding, edge sampling and color spaces; chained, masked and transformed effects; the filter-primitive inventory is frozen in G-1 |
| Color and scale | Defined conversions, premultiplication and interpolation; fractional DPI, tagged sRGB and wide-gamut input/output with explicit capability handling | Color-managed references, transparent gradients, scale transitions and gamut conversion; advanced formats unsupported on a device must report that fact |
| Recording and GPU | Reusable display recordings, offscreen rendering, batched marks and the public custom-surface integration | Reuse without reshaping/rebuilding unchanged content, bounded caches, resize/disposal/device-loss handling, declared GPU dependencies and transfers |
| Inspection and export | Inspectable drawing commands and resource costs; raster export and an extension path for vector output from the same scene | Public-only recording/replay/export consumers, documented vector fidelity and explicit rasterization of effects; no renderer internals required |

The current sRGB framebuffer, linear blending, and premultiplied-alpha contract
in VISION §7.2 remains the nucleus default. G-1 must specify how later wide-gamut
targets and reference browser color modes relate to that default. Color-policy
differences require labeled analytical and visual comparisons, not a claim of
byte-identical browser rendering. Hardware-dependent formats have a published
support matrix; silently dropping an effect or substituting a format is invalid.

## Public API authoring and consumers

Before freezing the API, compile and review examples for standard controls,
a diagram/vector editor, multilingual text, an image viewer, animated content,
layered/filter composition, reusable offscreen drawing, and a dense batched scene.
Each must use public APIs only. Include a standalone recording/replay consumer
without a widget tree. This general corpus defines platform acceptance; domain
adapters add integration coverage rather than defining graphics capabilities.
Review scoped state, immutable path/paint reuse, error handling, discoverability,
and resource ownership; judge clarity against equivalent browser programs.
The primitive API and resource model must follow VISION §5; graphics work cannot
introduce its own scheduler or bypass transaction and lifetime rules.

### Independent consumer: Intaglio

Intaglio support remains a required downstream acceptance case, delivered by an
optional `sirin-intaglio` adapter with no reverse dependency from graphics/core.
Its portability work does not block the general drawing API, recording backend,
or graphics nucleus. The integration has two prerequisites: its portable modules
must build and pass qualification on Scala Native, and Sirin must supply the
needed public drawing and text services. A downstream `sirin-intaglio` adapter
then translates `DeviceScene` into Sirin commands. It must:

- Pass Intaglio's backend conformance requirements, plus Sirin visual checks for
  paths, patterns, raster sampling, clipping, transforms, and text placement.
- Preserve batched points as batches; a point must not become a UI component.
- Translate resolved device coordinates once, preserving order, metadata and
  defined pattern/opacity rules; do not reconstruct plot semantics.
- Use Sirin font resolution and metrics for layout and the corresponding text
  services for painting. Use Intaglio picking from the same resolved scene and
  transform input coordinates through the adapter's mapping.
- Exercise resize/DPI transitions, hover, selection, linked plots, keyboard
  navigation, semantic labels/actions, and disposal using public extension APIs.

Native host qualification remains blocked until the Native prerequisite passes.
The adapter's recording/conformance work can proceed on JVM meanwhile; neither
that nor substitute scenes certify Native support. Track general graphics G-3
separately from consumer acceptance G-4, so an upstream portability failure does
not mislabel the graphics implementation as incomplete. G-4 and the full Stage 4
exit still require the promised integration or an explicit scope decision.
Intaglio integration alone does not cover the full graphics matrix.

## Gates and evidence

| Gate | Timing | Exit evidence |
|---|---|---|
| G-0: Subsystem and rendering design | Alongside G-1; design before nucleus integration | Standalone drawing/recording/preparation boundaries, module dependencies, GPU mappings, ownership/copy accounting, and a bounded ThorVG comparison protocol assigned to P0.3/G-2 |
| G-1: Contract and reference corpus | Design/authoring can proceed during Stage 1 | Operation inventory for every matrix row, provisional public APIs and compiled examples, pinned browser fixtures, expected semantics, platform matrix, and prespecified quality/performance budgets |
| G-2: Graphics nucleus and backend decision | Stage 2 exit | Public paths/paints/strokes, affine transforms, path clips, gradients, images, measured text, isolated opacity, reusable recordings and ordered instances; standalone and UI-host examples; geometric/image evidence; bounded wgpu/ThorVG comparison receipts and explicit backend decision |
| G-3: Complete 2D graphics | Stage 4 exit | Every matrix operation implemented and qualified, including effects, color, recording, readback/export and surface integration; no missing family hidden behind a completed nucleus |
| G-4: Consumer and quality qualification | Stage 4 exit | General authoring corpus across controls, diagrams/editors, text, images, animation and dense scenes; independent Intaglio Native/backend receipts; visual approval and matched-browser workload results; no unsupported superiority claim |

Each operation records implemented, demonstrated, blocked, or deferred/unsupported
status with a reason and links to evidence. Blocked/deferred required operations
keep G-3/G-4 open. Any change to the target must be an explicit design decision.
Stage 2 may pass its nucleus gate without claiming complete browser-level graphics.

Evidence is published under `docs/evidence/graphics/` when checks actually run:

- **Correctness:** independent geometry and compositing oracles, scoped-state and
  resource tests, renderer conformance, and deterministic recording checks on JVM
  and Native where applicable. Recording checks prove translation/order/resource
  declarations; they do not establish raster fidelity or GPU performance.
  Unavailable tests are skipped with a reason.
- **Visual quality:** matched scenes with pinned fonts and rendering settings at
  1x, 1.25x, 1.5x and 2x; adapter-specific goldens plus browser comparisons and
  human review of text, curves, thin strokes, gradients and effects. Thresholds
  are set per feature before accepting results. Pixel similarity alone cannot
  certify quality or override a semantic failure.
- **Performance:** matched browser/Sirin workloads for dense points, many curved
  paths, multilingual text, layered effects, raster updates, and pan/zoom/resize.
  Record CPU preparation, GPU time, input-to-display latency (p50/p95/p99), missed
  frames, engine allocations, total memory, transfer bytes, and teardown recovery.
  Freeze scene sizes, host/browser versions and numeric budgets in G-1 before
  tuning; include 60/120 Hz targets and warm/cold results. Report tradeoffs and
  failures rather than inferring speed from GPU acceleration.

Direct lowering to wgpu remains the implementation baseline while the bounded
ThorVG comparison tests the cost and quality of delegation. Shipping a second
backend is optional. A renderer that cannot meet these gates triggers an
evidence-based implementation or backend decision rather than lowering the API
or quality target. Existing native ABI, callback-thread, and pause gates apply.

The first deliverables are the general drawing/recording contract and corpus,
then the nucleus and bounded renderer comparison, followed by full capability
and host qualification. Intaglio Native portability and adapter recording tests
can proceed independently, joining the consumer acceptance track when ready.

The bounded triangle/texture tranche now has
[measured four-scale evidence](../evidence/graphics/2026-10-28-batch.md), including
full 320x180 corpus pixel comparisons and cold/warm/resource budgets. Production
packing, mounted host acceptance and the full G-1/G-2 exit remain open.
