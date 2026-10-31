package sirin.host

import sirin.*
import sirin.drawing.{PreparedText, RecordingCanvas}
import sirin.graphics.Drawing
import sirin.text.{DocumentTextLayout, ResolvedTextLayout}

final case class Box(x: Double, y: Double, width: Double, height: Double):
  require(Vector(x, y, width, height).forall(_.isFinite) && width >= 0 && height >= 0, "invalid host box")
  def contains(px: Double, py: Double): Boolean = px >= x && py >= y && px < x + width && py < y + height
  def intersect(other: Box): Box =
    val left = math.max(x, other.x)
    val top = math.max(y, other.y)
    Box(
      left,
      top,
      math.max(0, math.min(x + width, other.x + other.width) - left),
      math.max(0, math.min(y + height, other.y + other.height) - top)
    )
/** What was presented of a reporting layout; the owner writes it back before presenting (`Layout.reporting`). */
final case class ViewportRequest(instance: Long, viewport: Viewport)
final case class Placed(node: Node, bounds: Box, clip: Box, children: Vector[Placed], text: Option[TextLayout])
final case class LayoutResult(
    root: Placed,
    viewports: Vector[ViewportRequest],
    text: PreparedText
):
  def elements: Vector[Placed] =
    def visit(p: Placed): Vector[Placed] =
      Option.when(p.node.content.isInstanceOf[Content.Custom])(p).toVector ++ p.children.flatMap(visit)
    visit(root)

  /** Same clipped geometry as paint; native semantic transport consumes these parts. */
  def semanticParts: Vector[(Long, SemanticNode)] = elements.flatMap { p =>
    p.node.content match
      case Content.Custom(frame) =>
        frame
          .semantics(Size(p.bounds.width.lp, p.bounds.height.lp), text)
          .map { node =>
            val b = node.bounds
            val clipped = Box(
              p.bounds.x + b.origin.x.toDouble,
              p.bounds.y + b.origin.y.toDouble,
              b.size.width.toDouble,
              b.size.height.toDouble
            ).intersect(p.clip)
            frame.instance ->
              node.copy(bounds = Rect(Point(clipped.x.lp, clipped.y.lp), Size(clipped.width.lp, clipped.height.lp)))
          }
      case _ =>
        Vector.empty
  }
  /** `textFocus` paints the focused text control as focused in a base drawing that carries no other focus, so its
    * caret and selection stay with the text they belong to.
    */
  def drawing(
      theme: Theme,
      focus: Option[NodeId] = None,
      keyboardFocus: Boolean = false,
      pressed: Option[NodeId] = None,
      textFocus: Option[NodeId] = None
  ): Drawing =
    RecordingCanvas.record(theme, text) { canvas =>
      def paint(p: Placed): Unit =
        canvas.withClip(Rect(Point(p.clip.x.lp, p.clip.y.lp), Size(p.clip.width.lp, p.clip.height.lp))) {
          p.node.content match
            case Content.Button(_, _, enabled, _, primary) =>
              def rectangle(b: Box) = Rect(Point(b.x.lp, b.y.lp), Size(b.width.lp, b.height.lp))
              val base =
                if pressed.contains(p.node.id) then
                  Color.rgba(65, 112, 160)
                else if primary then
                  Color.rgba(45, 90, 140)
                else
                  Color.rgba(44, 53, 69)
              val focused = keyboardFocus && focus.contains(p.node.id)
              if focused then
                canvas.fill(rectangle(p.bounds), theme.color(ColorRole.Accent))
              val inner =
                if focused then
                  Box(p.bounds.x + 2, p.bounds.y + 2, math.max(0, p.bounds.width - 4), math.max(0, p.bounds.height - 4))
                else
                  p.bounds
              canvas.fill(rectangle(inner), base)
              canvas.text(
                p.text.get,
                Point((p.bounds.x + 12).lp, (p.bounds.y + 8).lp),
                theme.color(
                  if enabled then
                    ColorRole.OnSurface
                  else
                    ColorRole.Muted
                )
              )
            case Content.Custom(frame) =>
              canvas.withOffset(Point(p.bounds.x.lp, p.bounds.y.lp)) {
                frame.paint(
                  Size(p.bounds.width.lp, p.bounds.height.lp),
                  canvas,
                  ElementFeedback(
                    focus.orElse(textFocus).contains(p.node.id),
                    keyboardFocus,
                    pressed.contains(p.node.id)
                  )
                )
              }
            case _ =>
              ()
        }
        LayoutResult.group(canvas, p)(p.children.foreach(paint))
      canvas.fill(Rect(Point.origin, Size(root.bounds.width.lp, root.bounds.height.lp)), theme.color(ColorRole.Surface))
      paint(root)
    }

  /** Focus/press decoration composes above a reusable base through shared graphics. */
  def overlay(theme: Theme, focus: Option[NodeId], keyboard: Boolean, pressed: Option[NodeId]): Drawing =
    RecordingCanvas.record(theme, text) { canvas =>
      def rect(b: Box) = Rect(Point(b.x.lp, b.y.lp), Size(b.width.lp, b.height.lp))
      def visit(p: Placed): Unit =
        canvas.withClip(rect(p.clip)) {
          p.node.content match
            case Content.Custom(frame) =>
              canvas.withOffset(Point(p.bounds.x.lp, p.bounds.y.lp)) {
                frame.overlay(
                  Size(p.bounds.width.lp, p.bounds.height.lp),
                  canvas,
                  ElementFeedback(focus.contains(p.node.id), keyboard, pressed.contains(p.node.id))
                )
              }
            case Content.Button(_, _, _, _, _) =>
              val b = p.bounds
              if pressed.contains(p.node.id) then
                canvas.fill(rect(b), Color.rgba(50, 130, 220, 46))
              if keyboard && focus.contains(p.node.id) then
                val thickness = math.min(2, math.min(b.width, b.height) / 2)
                val color = theme.color(ColorRole.Accent)
                Vector(
                  Box(b.x, b.y, b.width, thickness),
                  Box(b.x, b.y + b.height - thickness, b.width, thickness),
                  Box(b.x, b.y, thickness, b.height),
                  Box(b.x + b.width - thickness, b.y, thickness, b.height)
                ).foreach(r => canvas.fill(rect(r), color))
            case _ =>
              ()
        }
        LayoutResult.group(canvas, p)(p.children.foreach(visit))
      visit(root)
    }

object LayoutResult:
  /** Children of a presented opacity node paint as one isolated group. */
  private def group(canvas: Canvas, p: Placed)(children: => Unit): Unit =
    p.node.content match
      case Content.Styled(Hint.Opacity(alpha), _) =>
        canvas.withOpacity(alpha)(children)
      case _ =>
        children

/** Pure full layout reference for the first host. No incremental-layout claim. */
object Layout:
  private final case class Measured(
      node: Node,
      width: Double,
      height: Double,
      children: Vector[(Measured, Double, Double)],
      text: Option[TextLayout]
  )
  def apply(
      root: Node,
      width: Double,
      height: Double,
      measureCache: ElementMeasureCache = new ElementMeasureCache,
      epochs: PhaseEpochs = PhaseEpochs()
  )(resolve: TextRequest => ResolvedTextLayout): LayoutResult =
    require(width.isFinite && height.isFinite && width > 0 && height > 0, "positive finite root size required")
    val prepared = scala.collection.mutable.LinkedHashMap.empty[TextRequest, ResolvedTextLayout]
    val documents = scala.collection.mutable.LinkedHashSet.empty[TextRequest]

    /** Element text; a request with hard line breaks prepares its paragraphs and resolves to their document. */
    def prepareElementText(request: TextRequest): Unit =
      val parts = PreparedText.paragraphs(request)
      if parts.size > 1 then
        documents += request
      parts.foreach { part =>
        require(prepared.contains(part) || prepared.size < 1024, "text preparation request budget exceeded")
        prepared.getOrElseUpdate(part, resolve(part))
      }
    def elementText(requests: Seq[TextRequest]): PreparedText =
      val parts = requests.flatMap(PreparedText.paragraphs).distinct.toVector
      PreparedText.fromResolved(
        parts.map(r => r -> prepared(r)),
        documents = requests.filter(r => DocumentTextLayout.hasBreaks(r.content)).distinct.toVector
      )
    def bounded(value: Double, limit: Double): Double = math.max(0, math.min(value, limit))
    def grow(node: Node): Boolean =
      node.content match
        case Content.Reference(_, child) =>
          grow(child)
        case Content.Styled(Hint.Grow, _) =>
          true
        case Content.Styled(_, child) =>
          grow(child)
        case _ =>
          false
    var measurements = 0
    def constraintsFor(
        w: Double,
        h: Double,
        fillWidth: Boolean,
        fillHeight: Boolean,
        minWidth: Double,
        minHeight: Double
    ): Constraints = Constraints(
      (
        if fillWidth then
          w
        else
          minWidth
      ).lp,
      w.lp,
      (
        if fillHeight then
          h
        else
          minHeight
      ).lp,
      h.lp
    )
    def validateConstraints(c: Constraints): Unit =
      val low = Vector(c.minWidth.toDouble, c.minHeight.toDouble)
      val high = Vector(c.maxWidth.toDouble, c.maxHeight.toDouble)
      require(
        low.forall(n => n.isFinite && n >= 0) && high.forall(n => !n.isNaN && n >= 0) && low.zip(high).forall(_ <= _),
        "invalid custom layout constraints"
      )
    def validateSize(size: Size, c: Constraints): Unit =
      val dims = Vector(size.width.toDouble, size.height.toDouble)
      require(
        dims.forall(n => n.isFinite && n >= 0) && size.width.compare(c.minWidth) >= 0 &&
          size.width.compare(c.maxWidth) <= 0 && size.height.compare(c.minHeight) >= 0 &&
          size.height.compare(c.maxHeight) <= 0,
        "custom size violates constraints"
      )
    def measure(
        node: Node,
        w: Double,
        h: Double,
        fillWidth: Boolean,
        fillHeight: Boolean,
        minWidth: Double = 0,
        minHeight: Double = 0
    ): Measured =
      measurements += 1
      require(measurements <= 100000, "layout measurement budget exceeded")
      def leaf(x: Double, y: Double, text: Option[TextLayout] = None) = Measured(
        node,
        if fillWidth then
          w
        else
          bounded(x, w)
        ,
        if fillHeight then
          h
        else
          bounded(y, h)
        ,
        Vector.empty,
        text
      )
      val measured =
        node.content match
          case Content.Custom(frame) =>
            val c = constraintsFor(w, h, fillWidth, fillHeight, minWidth, minHeight)
            validateConstraints(c)
            val requests = frame.measureTextRequests(c)
            requests.foreach(prepareElementText)
            val services = elementText(requests)
            val size = measureCache.measure(frame, c, epochs)(frame.measure(c, services))
            validateSize(size, c)
            Measured(node, size.width.toDouble, size.height.toDouble, Vector.empty, None)
          case Content.CustomLayout(frame, children) =>
            val c = constraintsFor(w, h, fillWidth, fillHeight, minWidth, minHeight)
            validateConstraints(c)
            val owner = Thread.currentThread()
            var active = true
            val cache = scala.collection.mutable.Map.empty[(Int, Constraints), Measured]
            val latest = scala.collection.mutable.Map.empty[Int, Measured]
            val placed = scala.collection.mutable.LinkedHashMap.empty[Int, (Measured, Double, Double)]
            def check(): Unit = require(
              active && (Thread.currentThread() eq owner),
              "layout children handle is closed or belongs to another thread"
            )
            def index(slot: Slot): Int =
              check()
              val i = children.indexWhere(_._1 == slot)
              require(i >= 0, "unknown layout slot")
              i
            def measureNode(child: Node, c: Constraints): Measured = measure(
              child,
              c.maxWidth.toDouble,
              c.maxHeight.toDouble,
              c.minWidth == c.maxWidth,
              c.minHeight == c.maxHeight,
              c.minWidth.toDouble,
              c.minHeight.toDouble
            )
            val handle =
              new LayoutChildren:
                def count: Int = {
                  check()
                  children.size
                }
                def measure(i: Int, constraints: Constraints, intrinsic: Boolean): Size =
                  check()
                  require(i >= 0 && i < children.size, "unknown layout child")
                  validateConstraints(constraints)
                  require(!placed.contains(i), "cannot measure a placed child")
                  require(
                    intrinsic || !cache.keysIterator.exists((j, c) => j == i && c != constraints),
                    "multiple constraints require intrinsic measurement"
                  )
                  val result = cache.getOrElseUpdate(
                    (i, constraints), {
                      require(cache.size < 10000, "custom child measurement budget exceeded")
                      measureChild(children(i)._2, constraints)
                    }
                  )
                  latest(i) = result
                  Size(result.width.lp, result.height.lp)
                private def measureChild(child: Node, c: Constraints): Measured = measureNode(child, c)
                def place(i: Int, at: Point): Unit =
                  check()
                  require(i >= 0 && i < children.size && !placed.contains(i), "unknown or already placed child")
                  require(at.x.toDouble.isFinite && at.y.toDouble.isFinite, "invalid child placement")
                  val child = latest.getOrElse(i, throw new IllegalArgumentException("measure child before placement"))
                  placed(i) = (child, at.x.toDouble, at.y.toDouble)
                def measure(slot: Slot, c: Constraints): Size = measure(index(slot), c, false)
                def place(slot: Slot, at: Point): Unit = place(index(slot), at)
                def grows(i: Int): Boolean =
                  check()
                  require(i >= 0 && i < children.size, "unknown layout child")
                  grow(children(i)._2)
                def collapsed(i: Int): Boolean =
                  check()
                  require(i >= 0 && i < children.size, "unknown layout child")
                  children(i)._2.content == Content.Empty
            val size =
              try frame.layout(c, handle)
              finally active = false
            validateSize(size, c)
            Measured(node, size.width.toDouble, size.height.toDouble, placed.values.toVector, None)
          case Content.Empty =>
            leaf(0, 0)
          case Content.Space(size) =>
            leaf(size, size)
          case Content.Reference(_, child) =>
            val c = measure(child, w, h, fillWidth, fillHeight)
            Measured(node, c.width, c.height, Vector((c, 0, 0)), None)
          case Content.Button(_, label, _, _, _) =>
            val request = TextRequest(label, TextStyle.Body, Lp(math.max(0, w - 24)))
            val text = prepared.getOrElseUpdate(request, resolve(request))
            leaf(text.size.width.toDouble + 24, text.size.height.toDouble + 16, Some(text))
          case Content.Styled(hint, child) =>
            hint match
              case Hint.Padding(x, y) =>
                require(x.isFinite && y.isFinite && x >= 0 && y >= 0, "invalid padding")
                val c = measure(child, math.max(0, w - 2 * x), math.max(0, h - 2 * y), fillWidth, fillHeight)
                Measured(node, bounded(c.width + 2 * x, w), bounded(c.height + 2 * y, h), Vector((c, x, y)), None)
              case Hint.Width(value) =>
                require(value.isFinite && value >= 0, "invalid width")
                val c = measure(child, bounded(value, w), h, true, fillHeight)
                Measured(node, bounded(value, w), c.height, Vector((c, 0, 0)), None)
              case Hint.Height(value) =>
                require(value.isFinite && value >= 0, "invalid height")
                val c = measure(child, w, bounded(value, h), fillWidth, true)
                Measured(node, c.width, bounded(value, h), Vector((c, 0, 0)), None)
              case Hint.Grow | Hint.Offset(_, _) | Hint.Opacity(_) =>
                val c = measure(child, w, h, fillWidth, fillHeight)
                Measured(node, c.width, c.height, Vector((c, 0, 0)), None)
          case Content.Stack(horizontal, gap, alignment, children) =>
            require(gap.isFinite && gap >= 0, "invalid stack gap")
            require(alignment != Alignment.Baseline, "baseline layout remains deferred")
            val active = children.filterNot(_.content == Content.Empty)
            val main =
              if horizontal then
                w
              else
                h
            val cross =
              if horizontal then
                h
              else
                w
            val available = math.max(0, main - gap * math.max(0, active.size - 1))
            def expanding(child: Node): Boolean = available.isFinite && grow(child)
            def childMeasure(child: Node, mainSize: Double, fillMain: Boolean): Measured = measure(
              child,
              if horizontal then
                mainSize
              else
                cross
              ,
              if horizontal then
                cross
              else
                mainSize
              ,
              if horizontal then
                fillMain
              else
                alignment == Alignment.Stretch && cross.isFinite
              ,
              if horizontal then
                alignment == Alignment.Stretch && cross.isFinite
              else
                fillMain
            )
            val fixed = active.filterNot(expanding).map(child => child -> childMeasure(child, available, false)).toMap
            val used =
              fixed
                .valuesIterator
                .map(c =>
                  if horizontal then
                    c.width
                  else
                    c.height
                )
                .sum
            val growing = active.count(expanding)
            val share =
              if growing == 0 then
                0.0
              else
                math.max(0, available - used) / growing
            val measuredChildren = active.map(child =>
              if expanding(child) then
                childMeasure(child, share, true)
              else
                fixed(child)
            )
            val naturalCross = measuredChildren
              .map(c =>
                if horizontal then
                  c.height
                else
                  c.width
              )
              .maxOption
              .getOrElse(0.0)
            val crossSize =
              if (
                  if horizontal then
                    fillHeight
                  else
                    fillWidth
                )
              then
                cross
              else
                bounded(naturalCross, cross)
            var cursor = 0.0
            val placed = measuredChildren.map { c =>
              val extent =
                if horizontal then
                  c.height
                else
                  c.width
              val offset =
                alignment match
                  case Alignment.Center =>
                    math.max(0, (crossSize - extent) / 2)
                  case Alignment.End =>
                    math.max(0, crossSize - extent)
                  case _ =>
                    0.0
              val item = (
                c,
                if horizontal then
                  cursor
                else
                  offset
                ,
                if horizontal then
                  offset
                else
                  cursor
              )
              cursor += (
                if horizontal then
                  c.width
                else
                  c.height
              ) + gap
              item
            }
            val naturalMain = bounded(
              math.max(
                0,
                cursor - (
                  if active.isEmpty then
                    0
                  else
                    gap
                )
              ),
              main
            )
            val mainSize =
              if (
                  if horizontal then
                    fillWidth
                  else
                    fillHeight
                )
              then
                main
              else
                naturalMain
            Measured(
              node,
              if horizontal then
                mainSize
              else
                crossSize
              ,
              if horizontal then
                crossSize
              else
                mainSize
              ,
              placed,
              None
            )
      measured.copy(width = math.max(minWidth, measured.width), height = math.max(minHeight, measured.height))
    val measured = measure(root, width, height, true, true).copy(width = width, height = height)
    val requests = Vector.newBuilder[ViewportRequest]
    def place(m: Measured, laidX: Double, laidY: Double, ancestorClip: Box): Placed =
      // A presented offset moves the subtree after layout, so paint, hit testing and semantics all follow it.
      val (x, y) =
        m.node.content match
          case Content.Styled(Hint.Offset(dx, dy), _) =>
            (laidX + dx, laidY + dy)
          case _ =>
            (laidX, laidY)
      val bounds = Box(x, y, m.width, m.height)
      val clip = ancestorClip.intersect(bounds)
      m.node.content match
        case Content.CustomLayout(frame, _) if frame.reportsViewport =>
          val left = m.children.map(_._2).minOption.getOrElse(0.0)
          val top = m.children.map(_._3).minOption.getOrElse(0.0)
          val right = m.children.map((c, dx, _) => dx + c.width).maxOption.getOrElse(0.0)
          val bottom = m.children.map((c, _, dy) => dy + c.height).maxOption.getOrElse(0.0)
          requests +=
            ViewportRequest(
              frame.instance,
              Viewport(
                Size(m.width.lp, m.height.lp),
                Rect(Point((clip.x - x).lp, (clip.y - y).lp), Size(clip.width.lp, clip.height.lp)),
                Size((right - left).lp, (bottom - top).lp)
              )
            )
        case _ =>
          ()
      m.node.content match
        case Content.Custom(frame) =>
          val size = Size(m.width.lp, m.height.lp)
          // Text semantics resolve from the same inventory, so assistive geometry matches paint.
          // Semantics see paint's requests; their own text resolves from the same inventory as paint.
          val painted = frame.textRequests(size)
          painted.foreach(prepareElementText)
          frame.semantics(size, elementText(painted)).flatMap(_.text.map(_.request)).foreach(prepareElementText)
        case _ =>
          ()
      // An opacity group does not clip, so an offset inside it is clipped only by the views around both.
      val inner =
        m.node.content match
          case Content.Styled(Hint.Opacity(_), _) =>
            ancestorClip
          case _ =>
            clip
      val children = m.children.map((child, dx, dy) => place(child, x + dx, y + dy, inner))
      Placed(m.node, bounds, clip, children, m.text)
    val result = place(measured, 0, 0, Box(0, 0, width, height))
    LayoutResult(
      result,
      requests.result(),
      PreparedText.fromResolved(prepared.toVector, documents = documents.toVector)
    )
