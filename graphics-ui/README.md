# sirin-graphics-ui

Pure JVM/Scala Native bridge from core `Canvas` to graphics `Drawing`. It depends
on `core`, `graphics` and `text-ui`. Each foundation stays independent of this
bridge. It implements the current core canvas recording interface, including
prepared text; it does not implement a GPU renderer or mounted UI host.

```scala
import sirin.*
import sirin.drawing.{PreparedText, RecordingCanvas, TextRequest}

// Preparation runs under an existing Committed capability, before recording.
// services supplies actual shared ResolvedTextLayout results.
val request = TextRequest("Preferences", TextStyle.Label)
val text = PreparedText.prepare(Vector(request), services)

val drawing = RecordingCanvas.record(theme, text) { canvas =>
  canvas.withOffset(Point(8.lp, 12.lp)) {
    canvas.fill(Rect(Point.origin, Size(180.lp, 40.lp)), theme.color(ColorRole.Surface), radius = 6.lp)
    canvas.withClip(Rect(Point.origin, Size(180.lp, 40.lp))) {
      canvas.text("Preferences", Point(10.lp, 8.lp), TextStyle.Label, theme.color(ColorRole.OnSurface))
    }
  }
}
```

The caller supplies `theme`, `services` and the existing read-only phase
capability. Native services are needed only while preparing. They may already
be closed when recording begins. The
[public Element integration test](../probes/paragraph/src/test/scala/sirin/graphics/text/CanvasBridgeSuite.scala)
measures, highlights and paints the same real multilingual layout through
core APIs; no mounted runtime or private element hook is used.

## Geometry, color and state

Core `Lp` values map 1:1 to graphics Double coordinates. No display scale,
rounding, baseline snap or font-size adjustment occurs. Core ARGB bytes convert
to straight-alpha sRGB through the same internal helper as the text adapter.
Premultiplication/color-space preparation remains a renderer responsibility.

`fill` and `stroke` use rectangle paths. A finite nonnegative radius clamps to
half the shorter side. `Path.roundedRectangle` uses four cubic corner
approximations with tangent continuity and control factor
`0.5522847498307936`; zero radius or a degenerate rectangle uses `Path.rectangle`.
This is explicit approximate geometry, not an exact circular-arc primitive or
pixel-quality qualification. Negative dimensions/radii and nonfinite geometry
reject. Strokes center on the path at a finite positive width; lines are open
paths with butt caps. These follow the existing graphics stroke contract.

`withOffset` records a translation scope; it does not mutate future stored
geometry. `withClip` establishes a rectangular path clip in the active space.
`withOpacity` records an isolated compositing group, preserving individual paint
alpha. Commands keep painter order. The same core canvas handle changes its
active graphics target inside by-name scopes, restoring it in `finally`.
Failed scopes roll back their complete commands, even when the caller catches
the error and continues. Each multi-run text call has an identity-transform
rollback scope, so it cannot leave a partial text operation after failure.

Recording is synchronous and confined to its creating Scala thread. Every
canvas operation, including theme/service access, rejects after the callback
returns or fails. Closing clears the active state, including references to
the supplied theme and prepared inventory. The finished drawing retains actual
command/font dependencies, not the canvas, native owners or preparation callback.
Theme behavior and application state-write rules remain caller/host contracts;
this bridge does not implement mounted phase validation.

## Prepared text

`TextRequest` keys exact content, `TextStyle` and requested maximum logical
width. Positive infinity means unwrapped; finite nonnegative widths mean the
existing shared wrapper. Requests do not normalize source text or infer styles.

`PreparedText.prepare(requests, services)(using Committed)` validates input
budgets, deduplicates requests in first-use order, and synchronously calls the
supplied real service once per distinct request. It requires actual
`ResolvedTextLayout` results, verifies source and width, and stores only those
immutable results. Preparation follows the existing slow-work/worker policy;
this function does not make synchronous work asynchronous.

`PreparedText.fromResolved` accepts explicit associations to already resolved
layouts, including after native closure. Source/width must match; conflicting
layouts for one request reject. The caller defines the style association.
`get` allows optional inspection; missing canvas lookups throw `TextNotPrepared`
with the exact request. There is no fallback to a live shaper or estimated text.

`canvas.textServices` is this lookup-only inventory, retaining the core
`Committed` call-site signature. `canvas.text(content, at, style, color)` requests
the unwrapped/infinite-width entry. Prepare that entry explicitly even if a
wrapped layout for the same string exists. To draw wrapped text, use
`canvas.text(canvas.textServices.layout(content, style, width), at, color)` or
pass an actual resolved layout directly. Direct layout drawing needs no
inventory entry and records the exact retained glyph objects and placements.
Missing-glyph/interpolated-caret diagnostics remain on the shared layout.

Default preparation budgets admit 1,024 input entries (duplicates included),
1,048,576 total input UTF-16 units, 262,144 final glyphs across distinct requests,
and 64 MiB of unique retained requested font-file payload. All requested fonts
count, including fallback faces not painted. FontData deduplicates by snapshot
identity; aliases to a layout still count glyphs per distinct request. Input
limits check before service calls; final glyph/font limits check as results
arrive. Failure returns no partial inventory and closes no caller service.
These bound inventory admission, not peak memory or cumulative shaping work;
the service's existing per-layout work budgets still apply. No throughput claim
or persistent cache/font-epoch policy is introduced.

```sh
sbt testJVM testNative    # includes graphicsUI on both platforms
sbt probeParagraph/test  # real-font preparation and public Element/Canvas tests
```

See [the bridge receipt](../docs/evidence/graphics/2026-10-28-canvas-ui.md).
GPU rendering, visual/pixel qualification, backend preparation, a mounted host,
IME/editing/navigation/accessibility behavior and the full graphics matrix remain
separate workstreams.
