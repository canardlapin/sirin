# sirin-graphics

Provisional standalone drawing and recording foundation, cross-built for JVM and
Scala Native. This module depends only on Scala, with no dependency on Sirin's UI
core, engine, Intaglio, a native library, or a GPU device. It implements a vector/image
recording subset of [the graphics plan](../docs/plans/2026-10-27-graphics-api.md).
G-0/G-1 remain in progress; this is not a qualified Stage 2 graphics nucleus.

```scala
import sirin.graphics.*

val box = Path.rectangle(Rect(0, 0, 40, 20))
val ink = Paint.Solid(Color.rgba8(30, 50, 90))
val symbol = Drawing.record(_.fill(box, ink))

val drawing = Drawing.record { canvas =>
  canvas.transformed(Affine.translation(10, 15)) { moved =>
    moved.clipped(box) { clipped =>
      clipped.layer(0.5) { group =>
        group.draw(symbol)
        group.stroke(box, ink, StrokeStyle(width = 2, join = LineJoin.Round))
      }
    }
  }
}
// drawing.commands is an immutable, ordered recording. No pixels were rendered.
```

The [public-only vector specimen](src/test/scala/sirin/graphics/examples/VectorSpecimen.scala)
compiles on both platforms. It records gradients, a dashed curve, shared geometry
instances and a selection overlay without mounting a widget tree.

The [image-viewer specimen](src/test/scala/sirin/graphics/examples/ImageSpecimen.scala)
records cropped/scaled views of one immutable RGBA8 snapshot:

```scala
val pixels = Array(255, 0, 0, 255).map(_.toByte)
val image = ImageSnapshot.copyRgba8(1, 1, pixels)
val picture = Drawing.record(_.image(image.full, Rect(0, 0, 100, 100), Sampling.Nearest))
val resources = DrawingResources.inspect(picture) // Either limit error or complete inventory
// resources.toOption.get.imageBytes == 4; this is retained payload, not GPU upload cost.
```

## Boundaries

`graphics` has no project dependencies. Existing `core`, `engine` and `testkit`
dependencies stay as they are. The pure [`text-ui`](../text-ui/README.md) bridge
now explicitly converts core `Lp`/geometry/color and UTF-16 positions for resolved
text, retaining one shared paragraph. The pure [`graphics-ui`](../graphics-ui/README.md)
bridge now lowers the current core Canvas interface to recordings, with explicit
prepared-text lookup and scoped geometry/state. Connecting recordings to a
mounted renderer/host remains open. Graphics values do not implicitly acquire UI units,
theme, scheduling or resource ownership. No kernel semantics change here.

Only immutable geometry, paints, styles, instance columns, owned image snapshots,
resolved glyph runs/font bytes and completed child drawings enter recordings. Recording
callbacks are executed synchronously and are never retained. There are no numeric
font/image IDs, borrowed pointers, native handles, disposal callbacks or mutable
external resource views. Image snapshots copy their pixel inputs/exports; callers
keep source arrays stable for the duration of the synchronous import.
Replacing an image creates a new snapshot and old drawings retain old pixels.
The [resource/text/preparation contract](../docs/plans/2026-10-27-graphics-resources.md)
specifies shared text and future backend ownership. The separate
[`textNative` service](../text-native/README.md) now shapes explicit static font
runs and produces grayscale masks. The isolated
[paragraph component](../probes/paragraph/README.md) adds script itemization,
supplied-face fallback and immutable unwrapped `ParagraphLayout` results whose
measurement and recording share exact runs and positions. Wrapped paragraphs
now reuse `TextLineLayout` with legal Unicode opportunities, per-line reshaping,
shared work budgets and explicit unbreakable overflow. Their shared `geometry`
provides grapheme-safe caret affinity, hit-testing and visual selection regions
from those same runs. Bounded CPU preparation and an isolated real GPU pixel
probe now implement an initial renderer slice; production host integration and
broader rendering qualification remain open.

`DrawingPreparation.prepare` returns a complete `PreparedDrawing` or a typed
unsupported/budget/invalid error. It expands child recordings and ordered
instances, resolves affine geometry/clips in Double precision and adaptively
flattens quadratic/cubic paths against a device-space tolerance. It retains the
exact source resources and target/device-generation identity, with explicit
work/depth/point bounds. Images and shaped glyph placements remain typed data
for backend preparation. Lines and quadratic/cubic strokes now expand in path
coordinates to a single union fill. Adaptive normal ribbons preserve original endpoint tangents for
caps/joins and bound both device-space normal error and dash arc-length error.
Stationary interior cusps reject explicitly. Caps, miter fallback, dash phase/odd
patterns, zero-length entries and closed seams remain supported.
Linear gradients retain transformed path-relative projections, ordered stops and
clamp/repeat/reflect semantics. Glyph coverage is now independent of paint, so
solid and gradient text use the identical positioned masks. Disabled
capabilities reject even inside invisible groups. No native handles, rasterization
or GPU dependencies enter this module. See the [offscreen probe](../probes/render/README.md) for the
first executable lowering and its measured ThorVG limitations.

`Drawing` owns its immutable command vector and retains shared immutable values
and image/font snapshots.
Child drawings are referenced, not flattened/copied. Begin/end markers are a
flat inspectable representation, generated only by the recorder. Callers cannot
construct an unbalanced `Drawing` from arbitrary commands. No per-frame compact
storage, allocation or throughput claim is made yet; packing can follow measured
preparation costs without changing drawing semantics.

## Recording contract

- Coordinates are finite `Double` values. Recording preserves them without
  conversion, tessellation, snapping or implicit DPI scaling. Hosts choose the
  mapping into logical coordinates and then backing pixels. Invalid inputs and
  nonfinite arithmetic results throw `IllegalArgumentException`. Inversion
  returns `None` for singular/nonfinite computations; Double rounding still
  limits ill-conditioned transforms. No GPU numeric range is promised here.
- `Affine` is `[a c tx; b d ty; 0 0 1]`. `first.andThen(second)` means first,
  then second. A nested transform maps child coordinates into parent coordinates:
  with parent matrix M and new local transform T, the new matrix is
  `T.andThen(M)`. Positive rotations are clockwise in a y-down host space.
- Paths retain moves, lines, quadratic/cubic control points and explicit closes.
  Each drawable subpath starts with a move. Close returns to that subpath's start;
  a subsequent segment may continue from there. `Path.roundedRectangle` adds
  clamped nonnegative radii with four explicit cubic corner approximations;
  it is not an exact circular-arc primitive. Fill and clipping implicitly
  close open subpaths; stroking closes only explicit `Close` segments. Winding
  rules are explicit. Arcs and geometric queries remain future work.
- Paint/style values are explicit on every operation. Colors are unpremultiplied
  tagged sRGB; future preparation converts to linear light and premultiplies for
  compositing. Linear gradients map paint coordinates into local path coordinates
  through `paintToLocal`, then through the current drawing transform. Colors
  interpolate in linear light with premultiplied alpha. Ordered coincident stops
  describe hard transitions; outside the stop range the nearest stop color
  extends to the 0/1 endpoints before clamp/repeat/reflect is applied.
- Stroke widths and dash lengths are in path coordinates and transform with the
  path. Width must be positive; no implicit zero-width hairline exists in this
  slice. Dash phase is preserved, odd patterns repeat twice, and individual zero
  lengths are legal provided the normalized period is finite and positive.
- Commands preserve painter order. `instances` shares one path, applies each
  instance transform and paint in supplied order, then the enclosing transform.
  A constant paint column is retained once; per-instance columns must match the
  transform count. Instances describe separate fills, including their individual
  alpha composition. Reordering overlapping translucent instances is invalid.
- A clip intersects the current clip at the transform active when established.
  Later transforms move subsequent content, not that established clip. Scope end
  restores the previous clip/transform. A child drawing inherits the current
  state and its balanced commands cannot leak changes to the caller.
- Images currently accept RGBA8 sRGB straight-alpha snapshots, positive in-bounds
  pixel-edge crops, explicit nearest/linear sampling and per-draw opacity.
  Fractional crops and destinations are retained; enclosing transforms/clips and
  isolated layers have their usual meaning. Pixel conversion, premultiplication,
  filtering and uploads remain backend work; no decode or output fidelity is
  established by recording an image command.
- `glyphs` retains a real `ShapedRun` and baseline origin, reading the same
  advances/positions and resolved font as measurement. It does not reshape,
  perform family lookup or invent paragraph/caret geometry. The native service
  supplies actual runs; `TextSource` offset conversion only identifies codepoint
  boundaries. Font bytes remain available after the native face is closed.
  The origin adds a translation to glyph positions; paints remain anchored in
  the current canvas-local space. A transformed scope moves both geometry and
  paints when that is intended.
- `layer(opacity)` records an isolated group. Its children paint into a
  transparent intermediate target; group opacity is applied once on composition.
  It is not distributed over children. The current subset uses source-over only;
  masks, other blend operators and backdrop-dependent effects remain future work.
- Scoped callbacks receive a canvas valid only until that callback exits. If a
  callback throws, its scope and all commands added within it are discarded,
  preserving enclosing state. Callers may catch the error and continue recording.
  An uncaught root error yields no `Drawing`. Escaped canvases/builders reject
  later use, and their temporary buffers are cleared after root construction.
  Recording/building are synchronous and confined to the calling thread.

`DrawingResources.inspect` iteratively visits the unique recording graph and
reports image/font snapshots in first-encounter order, retained pixel/font bytes, drawing
count and inspected command count. Shared drawings/snapshots are visited once.
Explicit budgets return an error instead of a partial inventory. Zero-opacity
or empty-destination operations still declare their snapshots. These counts do
not measure expanded placements, visible work, staging bytes or GPU memory.
The returned inventory strongly retains its snapshots.

These are recording semantics tested by command inspection and analytic geometry
checks. Rendering fidelity, rasterization, clipping pixels, alpha/color output,
GPU performance and device lifetimes require separate backend evidence.

## Verification and next work

```sh
sbt 'graphicsJVM/test' 'graphicsNative/test'
sbt testJVM testNative  # includes graphics plus the existing repository suites
```

The [isolated Unicode probe](../probes/unicode/README.md) now adds real
segmentation/raw bidi analysis and standalone `UnicodeAnalysis` data. It does
not position text or define carets. `BidiLine` adds source-preserving line L1/L2
levels and visual codepoint maps; those are not shaped glyph order. Its
artifact-dependent tests run separately.

Next: expand cusp support, paint families and the general reference corpus,
and implement texture atlases/production geometry
preparation and host composition under the native gates. Full paths/paint/compositing support, UI hosting, Intaglio Native/adapter
qualification and browser comparisons remain open in the graphics plan.

The bounded [triangle/texture continuation](../docs/evidence/graphics/2026-10-28-batch.md)
adds exact stroke-boundary compaction, CPU winding/clip tessellation, ordered GPU
batches and shared R8 glyph/RGBA image texture cells in the isolated render probe.
Its four-scale corpus and fixed resource/performance budgets are recorded in the
receipt. Production packing, mounted host integration and browser qualification
remain open.
