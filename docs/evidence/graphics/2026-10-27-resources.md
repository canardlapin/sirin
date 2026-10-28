# Image snapshots and recording resources

Date: 2026-10-27 (America/Toronto), local macOS arm64. Checkout based on
`91a0ceb` with concurrent uncommitted kernel/reconciliation changes preserved.
This note covers the second standalone graphics slice, following
[the recording foundation](2026-10-27-recording-foundation.md).

## Scope

Implemented `ImageSnapshot`, `ImageRegion`, sampling/opacity image commands and
`DrawingResources.inspect`. The standalone graphics project still has no UI,
engine, native library or GPU dependency. The image-viewer specimen compiles on
both platforms using only public APIs and one shared 2×2 RGBA8 snapshot.

[The resource plan](../../plans/2026-10-27-graphics-resources.md) specifies shared
text and backend preparation/ownership/invalidation. It also defines the bounded
wgpu/ThorVG comparison protocol. These are specifications, not implementations
of font loading, shaping, prepared resources or native interoperability. No
successful text/preparation stub or synthetic font handle was added.

## Verification

Toolchain: Scala 3.7.4, Scala Native 0.5.12, sbt 1.12.14, JDK 21.

The graphics suites pass **37 tests on each platform**, with no ignored tests:
GeometrySuite 6, ValuesSuite 7, DrawingSuite 10, ImageSuite 8 and
DrawingResourcesSuite 6. The 14 new cases establish:

- Caller input mutation and exported-array mutation cannot change a snapshot;
  original straight-alpha RGB channels are retained even at alpha zero.
- Padded-row imports pack pixels in row order; checked dimensions, strides,
  source lengths, offset arithmetic and crop bounds reject invalid/overflow cases
  before allocation/indexing. Exports respect destination boundaries.
- Recording preserves fractional source/destination coordinates, sampling,
  per-image opacity and enclosing transform/layer order. Old drawings retain old
  pixels after a new snapshot is created; rolled-back scopes retain no image use.
- Inspection returns unique snapshots in first-encounter order, keeps distinct
  equal-byte snapshots distinct, and computes retained pixel payload bytes.
- A 10,000-level graph with two references per child is inspected iteratively
  once per unique drawing (10,001 drawings, 20,001 commands, one 4-byte snapshot),
  without recursively expanding the astronomical number of draw placements.
- Exact budget boundaries succeed; exceeding a drawing/command budget returns
  an error rather than a partial dependency set. Opacity/empty destinations do
  not remove declared resources.

Commands and local logs:

```sh
sbt 'graphicsJVM/test' 'graphicsNative/test'
# /private/tmp/sirin-graphics-resources-tests-20261027.log
sbt testJVM testNative
# /private/tmp/sirin-graphics-resources-all-tests-20261027.log
```

The targeted run passed. After making command classification exhaustive, both
full aliases passed: graphics 37, core 7, engine 81 and testkit 5 on each platform.
The previous concurrent engine warning was corrected outside this graphics task.
No unrelated kernel or test source was changed by this task. The only subsequent
source edit clarifies the caller's responsibility to keep input pixels stable
during the synchronous copy; it changes no executable code. Local Markdown links,
fences and whitespace checks pass.

## Copy accounting

| Operation | Established behavior |
|---|---|
| Snapshot import | One owned packed pixel copy, `4 * width * height` bytes; source storage is not retained. Row padding is omitted. |
| `pixelsCopy` / `copyPixelsTo` | An explicit full payload copy into new/caller storage; no internal mutable array escapes. |
| Recording/inspection | Retains snapshot references; no pixel payload copy, content hashing or conversion. Inventory payload bytes exclude object/command overhead. |
| Native marshalling, conversion, staging, upload | Not implemented or measured; no zero-copy/GPU bandwidth claim. |

No native dependency was fetched or built. No backend probe, shader, device,
native font object, GPU cache or foreign callback is involved in this slice.

## Limits and open evidence

Recorded sampling/opacity is not evidence of correct filtered pixels, alpha
compositing or color conversion. No decoder, wide-gamut format, partial streaming
update, text service, UI host or native resource lifecycle is implemented here.
Backend pixel/copy/lifetime/timing checks cannot run until those implementations
exist; no passing placeholders replace them. Windows/Linux and browser fidelity
were not verified. No full capability row or G-0–G-4 gate is closed by this note.
