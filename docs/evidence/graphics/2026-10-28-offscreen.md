# Executable preparation, offscreen wgpu pixels and ThorVG seams

Date: 2026-10-28 (America/Toronto). Mote child:
`bd-01M61C1A9QVJ42G37Q2N2ZQP3B`, under the open general graphics track.
Start checkout `541a697`; concurrent kernel work advanced HEAD to `ae1817f`
during execution. Existing work was preserved. This slice changes no kernel
semantics, adopts no external production renderer and clears no native host gate.

## Implemented and tested

The pure JVM/Native graphics module now has executable `DrawingPreparation`.
It binds a result to device-session reference identity, backend/adapter metadata,
dimensions, affine logical-to-device mapping, format and device-space tolerance.
It expands shared drawings and instances in painter order, retains complete
image/font dependencies, freezes path clips at establishment and preserves
isolated group boundaries. Double-precision transforms precede adaptive
quadratic/cubic subdivision. The flatness test bounds transformed control-point
distance from each chord; depth/point exhaustion rejects rather than forcing a
visibly inaccurate chord. Large origins can be rebased before float conversion.
Unsupported strokes/gradients, disabled capabilities and explicit work budgets
reject the entire attempt, including invisible operations. No partial drawing or
GPU success placeholder escapes. Eight pure tests cover those contracts, plus
1,001 sampled cubic positions per scale under shear.

The isolated [`probeRender`](../../../probes/render/README.md) compiles actual
WGSL and submits it to the Apple M2 Pro Metal adapter through pinned wgpu-native
29.0.1.1. Scala prepares/admits/packs the program and supplies the shader. A
pointer-only C shim creates GPU resources, dispatches and reads back pixels.
Native callbacks never enter Scala. Blocking externs are explicitly inventoried
in VISION §5.4. Native callback state has independent ownership; readback state
also retains its device owner. Successful calls synchronize before returning.

This is a bounded brute-force reference interpreter, expressly authorized by
VISION §7.2, not a production compute-renderer decision. It executes 4×4 samples
per pixel with winding/even-odd fills, nested intersecting clips, ordered draws
and per-sample isolated source-over groups. Input images decode from sRGB to
linear premultiplied values before filtering/composition. Output is straight
sRGB RGBA8 payload in an RGBA8Unorm storage texture. Source crops map the sampling
domain; linear fetches clamp to the full snapshot extent. No UI geometry snapping
or production text contrast/gamma adjustment is applied by this reference path.

Already-shaped glyph IDs/positions and exact font snapshots survive face closure.
Preparation reopens those faces, verifies metrics/library versions, rasterizes
at the transform's maximum singular-value density and closes owners before
submission. No text is reshaped. Actual Latin `office` ligatures and rotated
Arabic `سلام` run masks render through both paths. A separate scalar FreeType
coverage oracle checks fractional Latin glyph placement at sampled pixels,
within one byte, at each scale. This does not certify paragraph hosting,
fallback discovery, atlas/contrast quality or browser text parity.

The [frozen subset contract](../../../probes/render/src/test/resources/contract.json)
uses a 160×120 logical viewport at 1×, 1.25×, 1.5× and 2×. It precedes expanded
pixel measurements. A sample-work safety ceiling was added before final
validation; no pixel oracle or tolerance was relaxed. Actual readbacks test
opaque interiors, holes, triangle/rounded paths, nested fixed-space clips,
overlapping isolated children, nearest/cropped images, linear transparent-edge
sampling, fractional coverage and real glyph masks. Direct linear-half-red over
blue matches `(0.735357, 0, 0.735357, 1)` within one RGBA8 byte.

Program limits bound source-pixel expansion, native glyph raster output,
operations, packed storage and per-sample work before submission. Unsafe float
coordinates/inverse mappings reject. GPU owners enforce creating-Scala-thread
confinement, generation checks and immediate logical invalidity after close.
Tests exercise 20 resize/replay transitions, five new device-owner cycles,
idempotent disposal and invalid-shader cleanup. Live context count returns to
zero. This is normal-path context cleanup evidence, not a driver-memory/RSS leak
proof, timeout/device-loss test or full mount/dispose qualification.

## ThorVG result and decision

The official [ThorVG 1.1.3 release](https://github.com/thorvg/thorvg/releases/tag/v1.1.3)
archive is pinned to SHA-256
`ef97785ed6d0f25ab84d6b4a0eed4325c68c4d80b1c2a61ea0b4029192fea746`.
The recipe fetches through `native/fetch-macos.sh --thorvg`, after a free-disk
check (184 GiB available before the build). It builds only the WebGPU engine
and [C binding](https://github.com/thorvg/thorvg/blob/v1.1.3/src/bindings/capi/thorvg_capi.h),
with threads/SIMD/loaders/tools disabled. It links the same wgpu-native dylib and
headers as the direct probe; the manifest pins all observed binaries and sources.

| Seam | Measured outcome |
|---|---|
| Device/instance/queue | The same actual objects serve both render paths; no second GPU stack |
| Texture target | Caller-created texture on that device works; pinned ThorVG requires BGRA8Unorm, direct compute uses RGBA8Unorm |
| Readback | Queue submission, draw/sync, texture-to-buffer copy and mapped readback succeed; BGRA export is swizzled to RGBA |
| Clips/order/grouping | Admitted opaque path/clip interiors and grouped-overlap seam work at four scales |
| Positioned text | Exact Sirin/FreeType masks hosted as copied raw image quads; no alternate shaping or positioned-glyph API claim |
| Images/sampling | Full-source mask/image quads only; cropped image rejects with status 13; Sirin sampling policy is not qualified |
| Color | **Fails** linear-light composition: half-red over blue gives approximately `(0.502, 0, 0.498, 1)` at every scale |
| Lifetime | Fresh canvas/engine synchronized and destroyed per call; shared wgpu owner returns to zero after disposal |

The initial RGBA8 texture caused a native validation abort because ThorVG's blit
pipeline expects BGRA8. The corrected format is explicit; this does not establish
arbitrary compositor-texture compatibility. Group/image opacity also quantizes
to eight bits. Broader blend/mask/gradient/path/stroke capabilities were not
qualified by this adapter. The color limitation is asserted and recorded as a
known failure, not hidden behind a widened pixel tolerance.

**ThorVG is not adopted.** Keep the Sirin-owned public semantics and existing
direct-renderer baseline. This negative configuration result is not a claim
that every ThorVG configuration is incapable of the required semantics. Before
any adoption, qualify linear color, sampling, complete feature coverage, native
sharing/submission/lifetime and retained-resource behavior. Full G-2 backend
selection remains open; this completes the bounded feasibility child only.

## Costs and limits of these measurements

Every copy is named in the probe contract: immutable import/export; linear vec4
expansion; FreeType mask allocation; Scala-to-native marshalling; direct storage
upload; GPU-to-mapped staging, native output, Scala array and snapshot copy.
ThorVG additionally converts prepared pixels back to straight sRGB bytes and
copies raw pictures into engine resources. Internal ThorVG uploads/allocations
are not instrumented. No zero-copy or equivalent-transfer claim is made.

Ten cold subset preparations, three warmup calls and ten measured synchronized
render/readbacks are retained as raw nanoseconds. The direct vector/image subset
uploads 3,184 bytes and reads 92,160 padded bytes per call. GPU timestamps,
input-to-display latency, allocations, driver memory and display intervals were
not measured. Both paths also time an identical two-fill/clip seam, but direct
pipeline reuse versus per-call ThorVG engine/canvas creation makes these
**diagnostic costs, not an engine throughput ranking**. The 1280×720 broad corpus,
120/600-frame protocol and frozen per-scene production budgets remain open.

Lossless [PNG readbacks](offscreen-20261028/pixels/direct-320x240.png),
[direct positioned text](offscreen-20261028/pixels/direct-text-320x240.png),
[ThorVG positioned text](offscreen-20261028/pixels/thorvg-text-320x240.png) and all
other scales were inspected for actual content. This is agent visual inspection
of a tiny seam fixture, not human visual acceptance or browser comparisons.

## Verification and retained failures

All 619 pre-host test cases passed across the successful runs, with no ignored
tests. The concurrently added host changes the current root aliases; its latest
compile blocker is recorded below, so this is not a clean-current-tree claim:

| Suite | JVM | Native |
|---|---:|---:|
| graphics | 52 | 52 |
| core | 9 | 9 |
| textUI | 2 | 2 |
| graphicsUI | 6 | 6 |
| engine | 170 | 170 |
| testkit | 30 | 30 |
| workbench | 7 | 7 |
| textNative | — | 7 |
| probeUnicode | — | 12 |
| probeParagraph | — | 40 |
| probeRender | — | 8 |

`sbt testJVM` completed in the first full command. Default `testNative` then
reported a killed native reconciliation property runner (signal 9) and runner
timeout, with 28 cases passed and one runner error. No failing assertion or
cause of the kill was established. The unchanged native suites passed after
serializing **execution only**, retaining all property counts and assertions:

```sh
sbt 'project testkitNative' 'set Test / parallelExecution := false' \
  'project root' testNative textNative/test probeUnicode/test \
  probeParagraph/test probeRender/test examples/compile
```

The successful command exited 0 at 10:16:40 EDT. The first attempted setting
referenced a nonexistent Scala build variable and stopped before tests; that
log is also retained. Initial ThorVG compilation lacked one include search path;
its corrected recipe built successfully. The first shader compilation rejected
WGSL's reserved `meta` name; renaming it fixed compilation. Empty adapter
description metadata was replaced by the actual adapter device-name field with
failure-path cleanup. Strict -Werror caught an unused test loop binding; it was
removed without changing assertions. All these failures remain in the receipt.

A final `sbt testJVM` refresh again passed the seven original platform suites,
then reached the newly added concurrent `hostJVM` project and failed compilation
at `host/src/main/scala/sirin/host/CommittedTree.scala:51`: a non-local `return`
inside `foldLeft` is a warning under Scala 3, rejected by -Werror. The host code
and kernel changes were left untouched. The extended current `testJVM` alias
therefore is not green; current host JVM/Native qualification belongs to that
concurrent workstream. The final source inventory is captured after these runs
in an actively edited workspace, not a frozen whole-repository candidate.

`git diff --check`, shell recipe syntax and JSON parsing passed. Examples compile.
The GPU executable's dynamic inventory includes the pinned wgpu-native dylib,
libc++ and libSystem; font/ThorVG libraries are static. It does not co-link another
Rust component. Local Metal execution does not establish hosted CI or other-OS
GPU qualification. Source/artifact hashes, logs, host/tool versions, diagnostic
timings and all-scale lossless images are in the
[receipt manifest](offscreen-20261028/manifest.json).

Next: production path/stroke/gradient preparation, expanded G-1 references and
resource/atlas reuse, then native host composition under its existing gates.
The browser-level capability/visual/authoring requirements remain unchanged.
