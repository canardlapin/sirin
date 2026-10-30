# Authoring a public element

Use `Element.simple(name, state, definition)` and `Element.Simple[S]` when one
state value is enough. A change invalidates layout, paint, hit testing and
semantics. Use the per-phase `ElementInputs` form when, for example, changing
color should preserve measurements. Keep phase values immutable.

The executable authoring example in
`examples/authoring/consumer/CurveControl.scala` deliberately lives outside the
`sirin` package. It uses a curved gradient stroke, public activation and semantics,
with no host imports. `core/src/main/scala/sirin/ButtonElement.scala` shows the
advanced form used by the standard Button.

## Drawing

Depend on graphics-ui and import `sirin.drawing.CanvasGraphics.*`. Inside paint,
`canvas.graphics` is the same Canvas2D used by standalone Drawing.record. Use
`sirin.graphics` paths, paints, images, positioned glyphs, instance batches and
recorded drawings directly. For example:

```scala
import sirin.*
import sirin.graphics as g
import sirin.drawing.CanvasGraphics.*

val curve = g.Path.build { path =>
  path.moveTo(0.lp, 10.lp)
  path.cubicTo(g.Point(10.lp, 0.lp), g.Point(20.lp, 20.lp), g.Point(30.lp, 10.lp))
}
// In an Element.paint callback:
canvas.graphics.stroke(curve, g.Paint.Solid(g.Color.rgba8(40, 100, 180)),
  g.StrokeStyle.logical(2.lp))
```

The shared Lp type represents logical lengths. Standalone consumers can import
`sirin.units.syntax.*`; UI consumers retain `sirin.*`. Device scaling is explicit
at RenderTarget preparation. Existing raw Double graphics constructors remain
available for backend coordinates. No implicit pixel snapping occurs.

Use Canvas2D `clipped`, `transformed` and `layer` scopes. The core convenience
canvas has matching names for rectangles and core transforms. Child handles
expire when their scope exits; recording handles cannot cross threads. Failed
scopes discard their commands. Immutable Drawing values can be embedded with draw.

## Text, input and lifetime

Declare measurement text with measureTextRequests, then use the supplied
lookup-only TextServices in the three-argument measure overload. Declare paint
text with textRequests and read it from canvas.textServices. An undeclared request
fails explicitly; paint never shapes text as a fallback.

Pure callbacks receive settled phase values and Committed, not a model reader.
Only owner-side ElementHandlers run under UiTxn. Keep a reusable phase definition
stable when reconstructing a control, and replace its handlers independently.
Removed instances reject stale actions. A View.Proxy exposes ordinary composition
without adding an engine-specific widget type.

ElementActivation opts into shared pointer-release, Enter and Space-release
behavior, including enablement and semantic activation. ElementFeedback and
paintOverlay provide focus/press feedback over retained base drawing. More general
pointer handlers can capture, release and receive local coordinates against the
accepted geometry. The current desktop supports only the shared capacity of 256
queued input facts and explicitly refuses other declared capacities.

Layout.named uses stable Slot names. Measure a child before placing it, and place
it at most once. Additional constraint measurements require intrinsic=true.
Unplaced children stay mounted but do not paint, receive input or expose semantics.
LayoutChildren handles cannot escape their layout callback.

This API is provisional. Arbitrary semantic parts have headless snapshots, but
native accessibility transport currently covers only button activation. TextField
composition/text ranges and real animation remain open work before API freeze.
