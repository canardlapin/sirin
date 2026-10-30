package sirin.graphics

import scala.collection.mutable.ArrayBuffer

/** A constant column avoids one identical value per mark. */
enum InstanceValues[+A]:
  case Constant(value: A)
  case PerInstance(values: Vector[A])

/** Transforms and paints have stable painter order. Geometry is shared, not expanded into one path or component per
  * instance.
  */
final case class InstanceBatch(transforms: Vector[Affine], paints: InstanceValues[Paint]):
  paints match
    case InstanceValues.Constant(_) =>
      ()
    case InstanceValues.PerInstance(values) =>
      require(values.size == transforms.size, "instance paint count must match transform count")

/** Flat, inspectable commands. Begin/end markers preserve local coordinate scope, clip establishment order, and
  * isolated compositing boundaries. Constructing a command alone cannot construct an invalid Drawing.
  */
enum DrawCommand:
  case Fill(path: Path, paint: Paint, rule: FillRule)
  case Stroke(path: Path, paint: Paint, style: StrokeStyle)
  case FillInstances(path: Path, batch: InstanceBatch, rule: FillRule)
  case Image(source: ImageRegion, destination: Rect, sampling: Sampling, opacity: Double)
  case Glyphs(run: ShapedRun, origin: Point, paint: Paint)
  case Draw(drawing: Drawing)
  case BeginTransform(transform: Affine)
  case EndTransform
  case BeginClip(path: Path, rule: FillRule)
  case EndClip
  case BeginLayer(opacity: Double)
  case EndLayer

/** Provisional drawing API. Paints/styles are explicit; scope state is lexical. A failed scope contributes no commands,
  * even if its error is caught by the enclosing drawing. Canvas capabilities expire when their body exits.
  */
trait Canvas2D:
  def fill(path: Path, paint: Paint, rule: FillRule = FillRule.NonZero): Unit
  def stroke(path: Path, paint: Paint, style: StrokeStyle = StrokeStyle()): Unit
  def instances(path: Path, batch: InstanceBatch, rule: FillRule = FillRule.NonZero): Unit
  def image(source: ImageRegion, destination: Rect, sampling: Sampling = Sampling.Linear, opacity: Double = 1): Unit
  def glyphs(run: ShapedRun, origin: Point, paint: Paint): Unit
  def draw(drawing: Drawing): Unit
  def transformed(transform: Affine)(body: Canvas2D => Unit): Unit
  def clipped(path: Path, rule: FillRule = FillRule.NonZero)(body: Canvas2D => Unit): Unit

  /** Isolated opacity: render children into a transparent intermediate target, then composite the group once. It is
    * never multiplied into each child.
    */
  def layer(opacity: Double)(body: Canvas2D => Unit): Unit

/** Immutable commands retaining shared immutable paths, paints, batches, image snapshots and child drawings. No
  * GPU/native resources, UI nodes or callbacks.
  */
final class Drawing private (val commands: Vector[DrawCommand]) extends Serializable

object Drawing:
  val empty: Drawing = new Drawing(Vector.empty)

  /** Synchronous, single-threaded recording. If body fails, no drawing escapes. */
  def record(body: Canvas2D => Unit): Drawing =
    val commands = ArrayBuffer.empty[DrawCommand]
    val canvas = new Recorder(commands)
    try
      body(canvas)
      new Drawing(commands.toVector)
    finally
      canvas.open = false
      commands.clear()

  private final class Recorder(commands: ArrayBuffer[DrawCommand]) extends Canvas2D:
    private val owner = Thread.currentThread()
    var open = true

    private def checkOpen(): Unit =
      require(Thread.currentThread() eq owner, "recording canvas belongs to another thread")
      require(open, "recording canvas is closed")
    private def add(command: DrawCommand): Unit =
      checkOpen()
      commands += command

    def fill(path: Path, paint: Paint, rule: FillRule): Unit = add(DrawCommand.Fill(path, paint, rule))
    def stroke(path: Path, paint: Paint, style: StrokeStyle): Unit = add(DrawCommand.Stroke(path, paint, style))
    def instances(path: Path, batch: InstanceBatch, rule: FillRule): Unit = add(
      DrawCommand.FillInstances(path, batch, rule)
    )
    def image(source: ImageRegion, destination: Rect, sampling: Sampling, opacity: Double): Unit =
      require(opacity.isFinite && opacity >= 0 && opacity <= 1, "image opacity must be in [0, 1]")
      add(DrawCommand.Image(source, destination, sampling, opacity))
    def glyphs(run: ShapedRun, origin: Point, paint: Paint): Unit = add(DrawCommand.Glyphs(run, origin, paint))
    def draw(drawing: Drawing): Unit = add(DrawCommand.Draw(drawing))

    def transformed(transform: Affine)(body: Canvas2D => Unit): Unit =
      scoped(DrawCommand.BeginTransform(transform), DrawCommand.EndTransform)(body)
    def clipped(path: Path, rule: FillRule)(body: Canvas2D => Unit): Unit =
      scoped(DrawCommand.BeginClip(path, rule), DrawCommand.EndClip)(body)
    def layer(opacity: Double)(body: Canvas2D => Unit): Unit =
      require(opacity.isFinite && opacity >= 0 && opacity <= 1, "opacity must be in [0, 1]")
      scoped(DrawCommand.BeginLayer(opacity), DrawCommand.EndLayer)(body)

    private def scoped(begin: DrawCommand, end: DrawCommand)(body: Canvas2D => Unit): Unit =
      checkOpen()
      val start = commands.size
      val child = new Recorder(commands)
      commands += begin
      var completed = false
      try
        body(child)
        commands += end
        completed = true
      finally
        child.open = false
        if !completed then
          commands.dropRightInPlace(commands.size - start)
