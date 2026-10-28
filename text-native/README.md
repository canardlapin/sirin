# sirin-text-native: first font-service component

Provisional Scala Native service for explicit static font faces and horizontal
single-script runs. Qualified locally on macOS arm64 with HarfBuzz 14.5.0 and
FreeType 2.15.0. It depends on `graphics.native`; the standalone `graphics` module
keeps no native dependency. This module is not aggregated by root and is tested
explicitly after fetching its artifacts, like the artifact-dependent probes.
Linux is unverified; Windows artifact delivery is unsupported by this recipe.

```sh
native/fetch-macos.sh --text
sbt textNative/test
sbt testJVM testNative
```

The fetch command builds minimal static libraries from checksum-pinned upstream
commits, with optional OS shapers, external compression/Unicode libraries, GPU
renderers and Rust backends disabled. It checks for at least 2 GiB free before
building. Requirements: C/C++ compiler, Meson, Ninja and Python 3. Source archives,
build directories, libraries and license copies remain in `native/vendor/`.
The test task verifies the checked-in Noto font/license hashes before running.
No Homebrew font library is linked or loaded.

```scala
import sirin.graphics.*
import sirin.graphics.text.NativeFont

// bytes contains a real static Unicode font file; the caller owns this input.
val data = FontData.copy(bytes)
val face = NativeFont.open(data, size = 24)
val run = try
  face.shape("office", ShapingOptions(Script.Latin, TextDirection.LeftToRight, "en"))
finally face.close()

val drawing = Drawing.record { c =>
  c.glyphs(run, Point(10.25, 40), Paint.Solid(Color(0, 0, 0)))
}
// Font bytes, glyph positions and ink/advance measurements survive face closure.
```

## Implemented contract

- `FontData` copies/owns font bytes, identified by snapshot identity. Copying
  alone does not validate a font; `NativeFont.open` must succeed before exposing
  a `ResolvedFont`. Callers keep input bytes stable while copying. Supported
  inputs are static scalable Unicode faces, at most 64 MiB. Variable/color faces
  are explicitly unsupported. A caller supplies exact font bytes/index; there
  is no family discovery or automatic fallback.
- A native face owns its own FreeType library/face and HarfBuzz font plus a
  native copy of the font bytes. Close is explicit and idempotent on the creating
  Scala thread. Native operations on another thread or after close are rejected.
  No finalizer, foreign thread or Scala callback participates. Expensive work
  belongs on a caller-owned worker under VISION §5.
- Logical size is in pixels and quantized to 1/64, exposed in `ResolvedFont`.
  Size must be positive and at most 4096. Shaping uses HarfBuzz OpenType functions
  with explicit script, horizontal direction and language; `liga` can be toggled.
  Script values are canonical four-letter tags; actual script properties are
  supplied by the Unicode component. Runs are limited to 1 MiB UTF-8
  and one million glyphs, with an early one-million-code-unit input guard.
- `ShapedRun` owns copied glyph IDs, UTF-8 clusters, advances, positions and ink
  extents, retaining the exact resolved font bytes/index/size and library versions.
  It exposes advance and placed ink bounds from the same result that paint reads.
  Coordinates are y-down relative to the baseline origin. The caller places that
  origin; RTL does not imply right-alignment. Missing glyph ID zero is preserved
  and reported, not silently replaced by a second font or measurement estimate.
  `shapeRange(source, range, options)` provides full logical source context while
  shaping only the selected codepoint-aligned UTF-8 range. Clusters are global
  source offsets; positions remain run-local. `ShapedRun.range` retains the item
  range. Source-edge BOT/EOT flags are explicit; text is never reversed for RTL.
  The four-argument overload accepts a containing context window, used for line
  boundaries. BOT/EOT then refer to that window and output clusters are rebased
  to the original source. `ShapedRun.context` records the exact input window.
- Single-glyph, multi-codepoint clusters additionally copy at most 64 GDEF
  ligature caret coordinates, relative to the glyph origin in logical units.
  The count must equal source scalar count minus one; absent, oversized or
  mismatched metadata stays empty. Retained caret storage is therefore bounded
  by input scalar count. `sg_text_carets` is pointer-only and `@blocking`.
  The paragraph geometry checks grapheme correspondence and positioned bounds
  before using it; these raw coordinates alone are not editing stops.
- `TextSource` rejects unpaired surrogates and maps typed UTF-8 offsets to UTF-16
  at codepoint boundaries. These mappings are not grapheme boundaries or caret
  stops. HarfBuzz's monotone grapheme clusters can group several source codepoints.
  The separate [paragraph integration component](../probes/paragraph/README.md)
  now supplies itemization, explicit supplied-face fallback and shared wrapped/
  unwrapped layout using this contextual range operation, including shared
  caret/hit/selection geometry. The pure [`text-ui`](../text-ui/README.md) bridge
  now adapts those results to core `TextLayout`; the isolated paragraph component
  also implements explicit-font `TextServices`. OS fallback and production UI
  host integration remain open.
- `Canvas2D.glyphs` records the exact run and origin without reshaping. Resource
  inspection retains unique `FontData` snapshots and reports `fontBytes` separately
  from image payload bytes. Font size/index are run dependencies, not new copies
  of the same file. No GPU text renderer or atlas exists yet.
- `rasterize` uses the same font bytes/face/glyph IDs in FreeType, with hinting,
  autohinting and bitmap strikes disabled. It produces owned packed 8-bit grayscale
  coverage, bearings and effective device scale. Size is quantized to 1/64 pixel;
  horizontal phase is explicitly a multiple of 1/64 in [0, 1), with zero y phase.
  This preserves shaped advances while allowing fractional glyph preparation.
  The default mask budget is 1,048,576 pixels; unsupported size/format/budget paths
  return errors. Source outlines are bounded before bitmap rendering. Empty space
  can yield an empty mask. Gamma/contrast, atlas packing, painting and compositing
  are not part of this operation.

## Native boundary and copies

The C shim exposes only pointers/scalars. HarfBuzz/FreeType structs stay in C;
Scala mirrors no C struct layout or by-value callback. Allocation, parsing,
metrics, shaping, rasterization and retirement externs are `@blocking`, listed
in VISION §5.4. Their C paths never call Scala. Scalar count/version/bitmap-pointer
accessors are plain bounded externs. There is no new kernel scheduling policy.

Font import copies caller bytes into `FontData`. Opening currently exports a
managed copy, copies into a temporary native zone, then copies into the face's
native storage. Shaping similarly copies UTF-8 into a native zone; glyph outputs
are copied before the native result is freed. Rasterization copies FreeType's
bitmap into a shim-owned packed buffer, then into an owned Scala `GlyphRaster`.
This is an explicit initial copy path, not zero-copy evidence. Immutable results
can outlive the face; mutable native results cannot escape their synchronous call.
Cross-thread publication still requires the explicit synchronization in VISION §5.

See [the evidence note](../docs/evidence/graphics/2026-10-27-text-native.md) for
tests and limitations. The complete shared text/preparation contract remains in
[the resource plan](../docs/plans/2026-10-27-graphics-resources.md). This does not
complete P0.3b's GPU atlas/golden gate or graphics G-0–G-4.
