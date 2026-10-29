package sirin.graphics

/** Identity of one actual backend device session. A new session gets a new
  * reference even on the same physical adapter; no native handle escapes.
  */
final class DeviceGeneration(val backendBuild: String, val adapter: String):
  require(backendBuild.nonEmpty && adapter.nonEmpty, "device description must be explicit")

final case class RenderTarget(device: DeviceGeneration, width: Int, height: Int,
    logicalToDevice: Affine, tolerance: Double = 0.125):
  require(width > 0 && height > 0 && width <= 4096 && height <= 4096,
    "target dimensions must be in [1,4096]")
  require(tolerance.isFinite && tolerance > 0 && tolerance <= 1, "invalid device-space tolerance")
  val format: String = "rgba8-srgb-straight"

final case class RenderCapabilities(images: Boolean = true, glyphs: Boolean = true,
    pathClips: Boolean = true, isolatedOpacity: Boolean = true,
    strokes: Boolean = true, linearGradients: Boolean = true)
final case class PreparationLimits(maxCommands: Int = 100000, maxPoints: Int = 262144,
    maxDepth: Int = 64, maxLayers: Int = 8, maxCurveDepth: Int = 24):
  require(maxCommands > 0 && maxCommands <= 1000000 && maxPoints > 0 && maxPoints <= 1048576 && maxDepth > 0 && maxDepth <= 128 &&
    maxLayers > 0 && maxLayers <= 8 && maxCurveDepth > 0 && maxCurveDepth <= 30, "invalid preparation limits")

enum PreparationFailure:
  case Unsupported(operation: String)
  case Budget(reason: String)
  case Invalid(reason: String)

final case class FlatPath(contours: Vector[Vector[Point]])
/** Gradient projection is relative to the first path vertex. Translation of a
  * prepared path therefore preserves its paint, including tile rebasing.
  */
enum PreparedPaint:
  case Solid(value: Color)
  case LinearGradient(x: Double, y: Double, offset: Double, stops: Vector[ColorStop], extend: Extend)

final case class PreparedClip(path: FlatPath, rule: FillRule)
enum PreparedCommand:
  case Fill(path: FlatPath, color: PreparedPaint, rule: FillRule, clips: Vector[PreparedClip])
  case Image(source: ImageRegion, destination: Rect, sampling: Sampling, opacity: Double,
    transform: Affine, clips: Vector[PreparedClip])
  case Glyphs(run: ShapedRun, origin: Point, color: PreparedPaint, transform: Affine, clips: Vector[PreparedClip])
  case BeginLayer(opacity: Double)
  case EndLayer

/** CPU preparation result, not uploaded resources or a successful renderer stub.
  * Target equality includes device-session identity, matrix, format and error.
  */
final class PreparedDrawing private[graphics] (val source: Drawing, val target: RenderTarget,
    val capabilities: RenderCapabilities, val commands: Vector[PreparedCommand],
    val resources: DrawingResources, val visitedCommands: Int, val preparedPoints: Int):
  def compatibleWith(other: RenderTarget): Boolean = target == other

object DrawingPreparation:
  private final case class Refusal(reason: PreparationFailure) extends RuntimeException
  def prepare(drawing: Drawing, target: RenderTarget, capabilities: RenderCapabilities = RenderCapabilities(),
      limits: PreparationLimits = PreparationLimits()): Either[PreparationFailure, PreparedDrawing] =
    try
      val engine = new Engine(target, capabilities, limits)
      val resources = DrawingResources.inspect(drawing, InspectionLimits(limits.maxCommands, limits.maxCommands.toLong)).fold(
        error => throw Refusal(PreparationFailure.Budget(error.toString)), identity)
      engine.visit(drawing, target.logicalToDevice, Vector.empty, 0, 0)
      Right(new PreparedDrawing(drawing, target, capabilities, engine.output.result(), resources, engine.visited, engine.points))
    catch
      case refused: Refusal => Left(refused.reason)
      case invalid: IllegalArgumentException => Left(PreparationFailure.Invalid(invalid.getMessage))

  private class Engine(target: RenderTarget, caps: RenderCapabilities, limits: PreparationLimits):
    val output = Vector.newBuilder[PreparedCommand]
    var visited = 0
    var points = 0
    private val active = scala.collection.mutable.HashSet.empty[Drawing]
    private def budget(ok: Boolean, reason: String): Unit =
      if !ok then throw Refusal(PreparationFailure.Budget(reason))
    private def supported(ok: Boolean, operation: String): Unit =
      if !ok then throw Refusal(PreparationFailure.Unsupported(operation))
    private def paint(value: Paint, transform: Affine, path: FlatPath): PreparedPaint = value match
      case Paint.Solid(color) => PreparedPaint.Solid(color)
      case g: Paint.LinearGradient =>
        supported(caps.linearGradients, "linear gradient")
        budget(g.stops.size <= 4096, "gradient stops")
        val inv = g.paintToLocal.andThen(transform).inverse.getOrElse(
          throw Refusal(PreparationFailure.Invalid("singular gradient mapping")))
        val dx=g.end.x-g.start.x;val dy=g.end.y-g.start.y
        val length=math.hypot(dx,dy)
        require(length.isFinite && length>0, "gradient extent overflow")
        val ux=dx/length;val uy=dy/length
        val anchor=path.contours.iterator.flatMap(_.headOption).nextOption().getOrElse(Point(0,0))
        val local=inv(anchor)
        val x=(ux*inv.a+uy*inv.b)/length
        val y=(ux*inv.c+uy*inv.d)/length
        val offset=((local.x-g.start.x)*ux+(local.y-g.start.y)*uy)/length
        require(Vector(x,y,offset).forall(_.isFinite), "gradient projection overflow")
        PreparedPaint.LinearGradient(x,y,offset,g.stops,g.extend)

    private def fill(path: Path, value: Paint, rule: FillRule, transform: Affine, clips: Vector[PreparedClip]): Unit =
      val flat=flatten(path,transform)
      output += PreparedCommand.Fill(flat,paint(value,transform,flat),rule,clips)

    def visit(drawing: Drawing, initial: Affine, initialClips: Vector[PreparedClip], depth: Int, initialLayers: Int): Unit =
      budget(depth <= limits.maxDepth && !active(drawing), "drawing depth/cycle")
      active += drawing
      var transform = initial
      var clips = initialClips
      var layers = initialLayers
      val matrices = scala.collection.mutable.ArrayBuffer.empty[Affine]
      val clipStack = scala.collection.mutable.ArrayBuffer.empty[Vector[PreparedClip]]
      try drawing.commands.foreach { command =>
        visited += 1
        budget(visited <= limits.maxCommands, "expanded commands")
        command match
          case DrawCommand.Draw(child) => visit(child, transform, clips, depth + 1, layers)
          case DrawCommand.BeginTransform(matrix) =>
            budget(matrices.size + clipStack.size + layers < limits.maxDepth, "scope depth")
            matrices += transform
            transform = matrix.andThen(transform)
          case DrawCommand.EndTransform => transform = matrices.remove(matrices.size - 1)
          case DrawCommand.BeginClip(path, rule) =>
            supported(caps.pathClips, "path clip")
            budget(matrices.size + clipStack.size + layers < limits.maxDepth, "scope depth")
            clipStack += clips
            clips = clips :+ PreparedClip(flatten(path, transform), rule)
          case DrawCommand.EndClip => clips = clipStack.remove(clipStack.size - 1)
          case DrawCommand.BeginLayer(alpha) =>
            supported(caps.isolatedOpacity, "isolated opacity")
            layers += 1
            budget(layers <= limits.maxLayers, "layer depth")
            output += PreparedCommand.BeginLayer(alpha)
          case DrawCommand.EndLayer => layers -= 1; output += PreparedCommand.EndLayer
          case DrawCommand.Fill(path, paint, rule) =>
            fill(path,paint,rule,transform,clips)
          case DrawCommand.Stroke(path, value, style) =>
            supported(caps.strokes, "stroke")
            val outline = try StrokePreparation.outline(path,style,transform,target.tolerance,limits.maxPoints-points,limits.maxCurveDepth)
            catch
              case e: IllegalArgumentException if e.getMessage.contains("cusp") =>
                throw Refusal(PreparationFailure.Unsupported("stroke cusp"))
              case e: IllegalArgumentException if e.getMessage.contains("budget") =>
                throw Refusal(PreparationFailure.Budget(e.getMessage))
            fill(outline,value,FillRule.NonZero,transform,clips)
          case DrawCommand.FillInstances(path, batch, rule) =>
            batch.transforms.indices.foreach { i =>
              visited += 1
              budget(visited <= limits.maxCommands, "expanded instances")
              val paint = batch.paints match
                case InstanceValues.Constant(value) => value
                case InstanceValues.PerInstance(values) => values(i)
              fill(path,paint,rule,batch.transforms(i).andThen(transform),clips)
            }
          case DrawCommand.Image(source, dest, sampling, alpha) =>
            supported(caps.images, "image")
            output += PreparedCommand.Image(source, dest, sampling, alpha, transform, clips)
          case DrawCommand.Glyphs(run, origin, paint) =>
            supported(caps.glyphs, "positioned glyphs")
            output += PreparedCommand.Glyphs(run, origin, this.paint(paint,transform,FlatPath(Vector(Vector(transform(Point(0,0)))))), transform, clips)
      }
      finally active -= drawing

    private def flatten(path: Path, transform: Affine): FlatPath =
      val contours = Vector.newBuilder[Vector[Point]]
      var line = scala.collection.mutable.ArrayBuffer.empty[Point]
      var current = Point(0, 0)
      var start = Point(0, 0)
      def add(p: Point): Unit =
        points += 1
        budget(points <= limits.maxPoints, "flattened points")
        line += p
        current = p
      def finish(): Unit =
        if line.nonEmpty then contours += line.toVector
        line = scala.collection.mutable.ArrayBuffer.empty[Point]
      def mid(a: Point, b: Point): Point = Point(a.x / 2 + b.x / 2, a.y / 2 + b.y / 2)
      def distance(p: Point, a: Point, b: Point): Double =
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = math.hypot(dx, dy)
        require(length.isFinite, "path extent overflow")
        if length == 0 then math.hypot(p.x - a.x, p.y - a.y)
        else
          val ux = dx / length
          val uy = dy / length
          val along = math.max(0, math.min(length, (p.x - a.x) * ux + (p.y - a.y) * uy))
          math.hypot(p.x - a.x - along * ux, p.y - a.y - along * uy)
      def cubic(a: Point, b: Point, c: Point, d: Point, depth: Int): Unit =
        val error = math.max(distance(b, a, d), distance(c, a, d))
        require(error.isFinite, "curve error overflow")
        if error <= target.tolerance / 2 then add(d)
        else
          budget(depth < limits.maxCurveDepth, "curve subdivision depth")
          val ab = mid(a, b); val bc = mid(b, c); val cd = mid(c, d)
          val abc = mid(ab, bc); val bcd = mid(bc, cd); val center = mid(abc, bcd)
          cubic(a, ab, abc, center, depth + 1)
          cubic(center, bcd, cd, d, depth + 1)
      path.segments.foreach {
        case PathSegment.MoveTo(p) => finish(); start = transform(p); add(start)
        case PathSegment.LineTo(p) => add(transform(p))
        case PathSegment.QuadraticTo(control, end) =>
          val b = transform(control); val d = transform(end); val a = current
          cubic(a, Point(a.x / 3 + b.x * (2.0 / 3), a.y / 3 + b.y * (2.0 / 3)),
            Point(d.x / 3 + b.x * (2.0 / 3), d.y / 3 + b.y * (2.0 / 3)), d, 0)
        case PathSegment.CubicTo(b, c, d) => cubic(current, transform(b), transform(c), transform(d), 0)
        case PathSegment.Close =>
          if current != start then add(start)
          finish()
          add(start)
      }
      finish()
      FlatPath(contours.result())
