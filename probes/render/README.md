# Isolated offscreen graphics probe

This is a real macOS arm64 Metal/wgpu pixel probe, not the production Sirin host.
It links one Rust library (`wgpu-native`), the C++ HarfBuzz/FreeType services and
pinned ThorVG 1.1.3. It deliberately does not co-link ICU4X or AccessKit. Full
paragraph/host integration still needs the combined Rust artifact and native
gates. No UI runtime or kernel semantics are changed.

```sh
native/fetch-macos.sh
native/fetch-macos.sh --text
native/fetch-macos.sh --thorvg
sbt probeRender/test
python3 probes/render/export-pixels.py /private/tmp/sirin-render-pixels
```

The ThorVG recipe checks disk space and archive SHA-256, builds only its WebGPU
engine/C binding, and uses the exact existing wgpu-native 29.0.1.1 headers and
dylib through an explicit pkg-config entry. Meson/Ninja are prerequisites. Fonts
are checked by `verifyFontFixtures`. This hardware probe is explicitly invoked,
outside portable `testJVM`/`testNative`; lack of Metal is a failure to run it,
never a successful pixel stub. Hosted CI GPU acceptance is not established.

`DrawingPreparation` in the pure graphics module expands ordered recordings,
freezes clips in device space, adaptively flattens curves and retains exact
image/font dependencies. Target identity includes a non-reused device generation,
dimensions, affine mapping, output format and error tolerance. Explicit `gpu.retain(program)` now creates a thread-confined retained program:
one uploaded storage buffer, output texture/view and bind group. Its `render()`
reuses those resources; staging/readback and returned snapshots still allocate.
Closing the resource or its parent retires it immediately and clears the retained
CPU program/dependencies. A device bounds its inventory to 64 programs and 64 MiB
of requested buffer/texture payload. `resourceStats` counts that payload and direct
storage upload bytes, excluding driver overhead, pipeline and staging allocation.
Whole-program retention remains available. The Native owner's `gpu.compile`
adds a bounded CPU decode/raster LRU and device-local shared vec4 arena. Independent
drawings retain fixed spans of immutable image pixels and paint-independent glyph
coverage. Pinned spans cannot evict; idle entries use LRU eviction. The default
GPU arena reserves 4 MiB with 128 entries; CPU decoded payload is bounded to 4 MiB
and 128 entries. Program buffer/texture payload plus arena capacity stays within
the 64 MiB device bound. Allocation, used bytes and upload/raster counters are
separate. Source snapshots/font bytes and object/driver overhead are excluded
from decoded/arena payload accounting. `SharedResourceLimits` can reduce those
bounds for pressure tests. Source identity, exact face/size/glyph/service versions
and requested raster density key reuse; paint/placement changes do not recolor or
rerasterize cached masks. No asynchronous frame queue is introduced.

The shared vec4 arena is a qualification resource store, without production R8
texture atlases, subpixel bins or a mounted host cache. The JVM transport continues
to consume the default inline `PixelProgram.compile` path; shared ownership is
qualified through this Native probe. It introduces no separate shaper or rasterizer.
Resource replacement requires preparing the new drawing;
`compatibleWith` checks only the target, not equality of different drawings.

`PixelProgram.compile` packs paths, clips, images and already-positioned glyph
quads. It reopens the retained exact static face, checks font-service metrics and
versions, rasterizes the stored glyph IDs and closes all font owners before
returning. It never shapes text. Mask density follows the largest singular value
of the affine transform (FreeType size quantization is retained in the mask).
Fractional glyph positions and arbitrary affine placement survive preparation.
This is grayscale-mask rendering, without the production atlas, subpixel bins,
contrast/gamma adjustment or OS font policy.

The direct WGSL interpreter executes the complete ordered program at each of
16 fixed subpixel samples. Winding/even-odd fills, intersecting path clips and
isolated source-over opacity compose per sample. Images decode to linear-light
premultiplied values **before** filtering/composition. Linear sampling clamps
texel fetches to the snapshot extent; the source crop determines the mapped
domain, so a filtered fractional crop may sample neighboring snapshot texels.
Output is unpremultiplied and encoded as sRGB bytes in an RGBA8Unorm texture;
`rgba8-srgb-straight` names the payload contract, not a storage-texture sRGB format.
Polyline and admitted Bezier normal ribbons lower to one nonzero union fill
before upload, preserving translucent overlap. Width/dashes remain in path coordinates under affine maps.
Butt/round/square caps, bevel/round/miter joins, limits, dash phase/odd/zero entries
and closed seams are supported. Curves bound center/normal deviation and the
aggregate chord-length deficit used for dash placement. Stationary interior
cusps reject explicitly; original segment endpoint tangents drive caps/joins.
Linear gradients project device samples into transformed paint coordinates and interpolate linear
premultiplied colors, including ordered coincident stops and all three extensions.
Path-relative projection origins survive the existing desktop tile translation.
Solid/gradient glyph paints multiply sampled paint-independent mask coverage at
each device sample. Radial/conic paints, patterns, UI edge snapping, filters,
shadows and other blend modes remain unsupported. Unsupported preparation
rejects the whole attempt, including invisible operations; nothing silently drops into a success result.

This brute-force interpreter is a bounded reference slice. It is not adoption
of a production compute renderer. The limits are 1,048,576 submitted pixels,
1,024 operations, 2,097,152 packed vec4 values, eight isolated layers and a
500-million sample-work safety ceiling. Source-pixel expansion, glyph masks,
recursion, command/point/subdivision budgets and float-conversion precision are
checked. Packed edge coordinates are bounded to ±1,048,576 device units;
gradient parameter extrema over the target are bounded to ±65,536 before WGSL
projection/extension arithmetic. Double transforms can rebase large origins before float packing; values
that cannot preserve the stated error are rejected. There is no silent low
precision fallback. These bounds do not constitute real-time guarantees.

`OffscreenGpu` is confined to its creating Scala thread. Native callbacks update
native-owned state only; all potentially blocking externs are `@blocking` and
inventoried in VISION §5.4. Requests and readback callbacks hold native references
independently of the caller, preventing callback-state use after a timed-out call.
Normal rendering is synchronized; no asynchronous frame queue, device-loss
recovery or timeout/cancellation qualification is claimed. Returned snapshots
own copies of bytes. Disposal forbids subsequent submission and retires the GPU
owner without requiring a later frame. The live-owner counter checks context
cleanup, not total driver memory or absence of every possible native leak.

## ThorVG comparison

Both paths use the **same actual wgpu device, instance and queue**. Each call
allocates a texture on that device. The direct path uses RGBA8Unorm; pinned
ThorVG's blit pipeline requires BGRA8Unorm, with a channel swizzle on exported
readback. Passing RGBA8 to that pipeline aborted validation in the initial
experiment; that failure is retained in the evidence. This probe does not prove
arbitrary external compositor-texture formats or asynchronous interleaving.

The comparison adapter builds ThorVG paints/scenes via its C API, supplies the
owned texture target, draws/synchronizes, destroys the canvas and terminates its
thread-disabled engine. Positioned FreeType masks are copied to raw image quads;
there is no alternate text shaper or claim that ThorVG exposes a positioned-glyph
API. ThorVG owns tessellation, batching, internal resources and shaders in this
comparison. General text integration is not certified by drawing those masks.

Opaque path/clip interiors and positioned-mask hosting work in the measured
subset. Cropped images explicitly reject with status 13; gradient fills reject with status 14. The adapter does not
qualify ThorVG's sampling policy (its C binding does not receive Sirin's sampling
flag). Opacity is quantized to eight bits. **The pinned configuration fails
Sirin's linear-light composition oracle:** half-red over blue is approximately
`(0.502, 0, 0.498)` rather than `(0.735, 0, 0.735)`. The test records and asserts
this known mismatch; passing seam tests are not color-conformance passes.
ThorVG is not adopted, and the public Sirin contract is unchanged.

Copies are explicit: snapshot import/export; CPU linear-premultiplied vec4 pixel
expansion; fresh FreeType mask storage; Scala-to-native program marshalling;
direct GPU storage upload; GPU readback to mapped staging, native output, Scala
array and immutable snapshot. ThorVG additionally converts packed pixels back
to straight sRGB bytes and copies raw images into its own resources. Its
engine-internal uploads/allocations are not yet instrumented. No zero-copy or
end-to-end transfer parity claim is made.

The [frozen subset contract](src/test/resources/contract.json) and tests exercise
1×/1.25×/1.5×/2×. Ten cold preparations, three warmups and ten synchronized
submission/readback timings are diagnostic receipts, not the full 1280×720,
120/600-frame G-1/G-2 protocol, GPU timestamps or display latency. See the
[evidence note](../../docs/evidence/graphics/2026-10-28-offscreen.md).
The direct pipeline persists for a GPU owner; this seam adapter creates/destroys
ThorVG's engine/canvas on each call. Its internal uploads and pipeline work are
included in elapsed time. Those unequal setup paths prohibit ranking engine
throughput from the tiny timing receipts. A proper comparison must normalize
retained resources and run the full frozen protocol after correctness admission.


The [continuation contract](../../docs/evidence/graphics/strokes-gradients-20261028/contract.json)
freezes the stroke/gradient subset and retention bounds before pixel validation.
The continuation evidence is in
[the stroke/gradient receipt](../../docs/evidence/graphics/2026-10-28-strokes-gradients.md).
The older receipt remains the record of the earlier implementation.


The [curves/reuse contract](../../docs/evidence/graphics/curves-reuse-20261028/contract.json)
and [receipt](../../docs/evidence/graphics/2026-10-28-curves-reuse.md) cover the next
bounded continuation. Previous receipts remain historical records of their slices.
