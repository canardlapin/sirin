package demo.diagram

import sirin.*

object DiagramLimits:
  val nodes = 512
  val edges = 2048
  val history = 64
  val coordinate = 1000000.0
  def bounded(value: Double): Double = math.max(-coordinate, math.min(coordinate, value))
  def finite(point: Point): Boolean = Vector(point.x.toDouble, point.y.toDouble).forall(_.isFinite)

final case class DiagramNode(id: Long, label: String, at: Point):
  require(id > 0 && label.nonEmpty && label.length <= 80, "invalid diagram node identity or label")
  require(
    DiagramLimits.finite(at) && Vector(at.x.toDouble, at.y.toDouble).forall(math.abs(_) <= DiagramLimits.coordinate),
    "diagram node is outside the coordinate budget"
  )
  sirin.graphics.TextSource(label)

final case class DiagramEdge(from: Long, to: Long)

final case class DiagramGraph(nodes: Vector[DiagramNode], edges: Vector[DiagramEdge]):
  require(nodes.size <= DiagramLimits.nodes && edges.size <= DiagramLimits.edges, "diagram size budget exceeded")
  require(nodes.map(_.id).distinct.size == nodes.size, "duplicate diagram node")
  private[diagram] val index = nodes.map(node => node.id -> node).toMap
  require(
    edges.distinct.size == edges.size && edges.forall(e =>
      e.from != e.to && index.contains(e.from) &&
        index.contains(e.to)
    ),
    "duplicate, dangling or self-referencing diagram edge"
  )

final case class DiagramCamera(origin: Point = Point.origin, scale: Double = 1):
  require(
    scale.isFinite && scale >= 0.125 && scale <= 4 && DiagramLimits.finite(origin) &&
      Vector(origin.x.toDouble, origin.y.toDouble).forall(math.abs(_) <= DiagramLimits.coordinate),
    "invalid diagram camera"
  )
  def world(at: Point): Point = Point((at.x - origin.x) / scale, (at.y - origin.y) / scale)
  def screen(at: Point): Point = Point(at.x * scale + origin.x, at.y * scale + origin.y)

final case class DiagramScene(graph: DiagramGraph, camera: DiagramCamera, selected: Set[Long])

private[diagram] final case class DiagramHistory(
    graph: DiagramGraph,
    undo: Vector[DiagramGraph] = Vector.empty,
    redo: Vector[DiagramGraph] = Vector.empty
)
private[diagram] final case class DiagramGesture(
    graph: DiagramGraph,
    camera: DiagramCamera,
    anchor: Point,
    pointer: Point,
    nodes: Set[Long],
    selectionBefore: Set[Long],
    pan: Boolean,
    button: PointerButton
):
  def scene: DiagramScene =
    val delta = pointer - anchor
    if pan then
      val origin = camera.origin + delta
      DiagramScene(
        graph,
        camera.copy(origin =
          Point(
            DiagramLimits.bounded(origin.x.toDouble).lp,
            DiagramLimits.bounded(origin.y.toDouble).lp
          )
        ),
        nodes
      )
    else
      DiagramScene(
        DiagramGeometry.shift(
          graph,
          nodes,
          Point((delta.x.toDouble / camera.scale).lp, (delta.y.toDouble / camera.scale).lp)
        ),
        camera,
        nodes
      )

object DiagramGeometry:
  val nodeSize: Size = Size(132.lp, 44.lp)
  def rect(node: DiagramNode): Rect = Rect(node.at, nodeSize)
  def screen(node: DiagramNode, camera: DiagramCamera): Rect =
    Rect(camera.screen(node.at), Size(nodeSize.width * camera.scale, nodeSize.height * camera.scale))
  def intersects(rect: Rect, size: Size): Boolean =
    rect.origin.x.toDouble < size.width.toDouble && rect.origin.y.toDouble < size.height.toDouble &&
      (rect.origin.x + rect.size.width).toDouble > 0 && (rect.origin.y + rect.size.height).toDouble > 0
  def visible(node: DiagramNode, camera: DiagramCamera, size: Size): Boolean = intersects(screen(node, camera), size)
  def hit(scene: DiagramScene, viewport: Size, at: Point): Hit =
    if !Rect(Point.origin, viewport).contains(at) then Hit.Miss
    else
      scene.graph.nodes.reverseIterator.find(n => rect(n).contains(scene.camera.world(at)))
        .fold(Hit.Self)(node => Hit.Part(PartId(node.id)))
  def shift(graph: DiagramGraph, selected: Set[Long], by: Point): DiagramGraph =
    require(DiagramLimits.finite(by), "invalid diagram displacement")
    val moving = graph.nodes.filter(node => selected(node.id))
    if moving.isEmpty then graph
    else
      def clamp(value: Double, positions: Vector[Double]): Double =
        math.max(
          -DiagramLimits.coordinate - positions.min,
          math.min(DiagramLimits.coordinate - positions.max, value)
        )
      val delta = Point(
        clamp(by.x.toDouble, moving.map(_.at.x.toDouble)).lp,
        clamp(by.y.toDouble, moving.map(_.at.y.toDouble)).lp
      )
      graph.copy(nodes = graph.nodes.map(node => if selected(node.id) then node.copy(at = node.at + delta) else node))

object DiagramGraph:
  val sample: DiagramGraph = DiagramGraph(
    Vector(
      DiagramNode(1, "Stimulus", Point(40.lp, 100.lp)),
      DiagramNode(2, "Encoder", Point(240.lp, 70.lp)),
      DiagramNode(3, "Readout", Point(440.lp, 150.lp))
    ),
    Vector(DiagramEdge(1, 2), DiagramEdge(2, 3))
  )
  def grid(count: Int): DiagramGraph =
    require(count >= 0 && count <= DiagramLimits.nodes, "invalid grid count")
    DiagramGraph(
      Vector.tabulate(count)(i =>
        DiagramNode(
          i.toLong + 1,
          s"Node ${i + 1}",
          Point(((i % 16) * 155 + 20).lp, ((i / 16) * 70 + 20).lp)
        )
      ),
      Vector.tabulate(math.max(0, count - 1))(i => DiagramEdge(i.toLong + 1, i.toLong + 2))
    )
