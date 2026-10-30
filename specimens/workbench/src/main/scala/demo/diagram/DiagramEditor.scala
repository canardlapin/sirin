package demo.diagram

import sirin.*

/** Public-API model. Gesture previews never overwrite the committed graph or history. */
final class DiagramEditor(scope: Scope, initial: DiagramGraph = DiagramGraph.sample):
  private val history = scope.cell(DiagramHistory(initial))
  private val selected = scope.cell(Set.empty[Long])
  private val cameraCell = scope.cell(DiagramCamera())
  private val gesture = scope.cell(Option.empty[DiagramGesture])
  private val panCell = scope.cell(false)
  private val serial = scope.cell(initial.nodes.map(_.id).maxOption.getOrElse(0L))
  private val pivot = scope.cell(Point(300.lp, 180.lp))
  private val canvasRef = scope.ref()
  val graph: Signal[DiagramGraph] = history.map(_.graph)
  val selection: Signal[Set[Long]] = selected
  val panning: Signal[Boolean] = panCell
  val dragging: Signal[Boolean] = gesture.map(_.nonEmpty)
  val scene: Signal[DiagramScene] = (graph, cameraCell, selection, gesture).mapN { (graph, camera, selected, active) =>
    active.fold(DiagramScene(graph, camera, selected))(_.scene)
  }
  val camera: Signal[DiagramCamera] = scene.map(_.camera)
  private val idle = dragging.map(!_)
  scope.effect(canvasRef.hasFocus) { focused =>
    if !focused && !canvasRef.hasFocus.get then cancelGesture()
  }
  private def commit(next: DiagramGraph)(using UiTxn): Unit =
    val old = history.get
    if next != old.graph then
      history.set(DiagramHistory(next, (old.undo :+ old.graph).takeRight(DiagramLimits.history)))
  private def sanitizeSelection()(using UiTxn): Unit = selected.update(_ intersect graph.get.index.keySet)
  def select(ids: Set[Long])(using UiTxn): Unit =
    UiTxn.require(!dragging.get, "finish the active gesture first")
    UiTxn.require(ids.subsetOf(graph.get.index.keySet), "unknown diagram selection")
    selected.set(ids)
  val undo: Command[Unit, Unit] = scope.action[Unit](
    "diagram.undo",
    "Undo",
    (idle, history).mapN((ready, h) => ready && h.undo.nonEmpty)
  ) { _ =>
    val h = history.get
    h.undo.lastOption.foreach(previous =>
      history.set(DiagramHistory(
        previous,
        h.undo.dropRight(1),
        (h.redo :+ h.graph).takeRight(DiagramLimits.history)
      ))
    )
    sanitizeSelection()
  }
  val redo: Command[Unit, Unit] = scope.action[Unit](
    "diagram.redo",
    "Redo",
    (idle, history).mapN((ready, h) => ready && h.redo.nonEmpty)
  ) { _ =>
    val h = history.get
    h.redo.lastOption.foreach(next =>
      history.set(DiagramHistory(
        next,
        (h.undo :+ h.graph).takeRight(DiagramLimits.history),
        h.redo.dropRight(1)
      ))
    )
    sanitizeSelection()
  }
  val delete: Command[Unit, Unit] = scope.action[Unit](
    "diagram.delete",
    "Delete",
    (idle, selection).mapN((ready, ids) => ready && ids.nonEmpty)
  ) { _ =>
    val gone = selected.get
    val g = graph.get
    commit(DiagramGraph(g.nodes.filterNot(n => gone(n.id)), g.edges.filterNot(e => gone(e.from) || gone(e.to))))
    selected.set(Set.empty)
  }
  val connect: Command[Unit, Unit] = scope.action[Unit](
    "diagram.connect",
    "Connect",
    (idle, scene).mapN { (ready, s) =>
      val ids = s.selected.toVector.sorted
      ready && ids.size == 2 && s.graph.edges.size < DiagramLimits.edges &&
      !s.graph.edges.contains(DiagramEdge(ids.head, ids.last))
    }
  ) { _ =>
    val ids = selected.get.toVector.sorted
    commit(graph.get.copy(edges = graph.get.edges :+ DiagramEdge(ids.head, ids.last)))
  }
  val add: Command[Unit, Unit] = scope.action[Unit](
    "diagram.add",
    "Add node",
    (idle, graph, serial).mapN((ready, g, id) => ready && g.nodes.size < DiagramLimits.nodes && id < Long.MaxValue)
  ) { _ =>
    val id = Math.addExact(serial.get, 1L)
    val at = cameraCell.get.world(pivot.get)
    val bounded = Point(DiagramLimits.bounded(at.x.toDouble).lp, DiagramLimits.bounded(at.y.toDouble).lp)
    commit(graph.get.copy(nodes = graph.get.nodes :+ DiagramNode(id, s"Node $id", bounded)))
    serial.set(id)
    selected.set(Set(id))
  }
  val cancel: Command[Unit, Unit] = scope.action[Unit]("diagram.cancel", "Cancel", dragging)(_ => cancelGesture())
  val panMode: Command[Unit, Unit] = scope.action[Unit]("diagram.pan", "Pan mode", idle)(_ => panCell.update(!_))
  def zoom(at: Point, factor: Double)(using UiTxn): Unit =
    UiTxn.require(!dragging.get, "finish the active gesture first")
    UiTxn.require(DiagramLimits.finite(at) && factor.isFinite && factor > 0, "invalid zoom")
    val old = cameraCell.get
    val world = old.world(at)
    val scale = math.max(0.125, math.min(4, old.scale * factor))
    cameraCell.set(DiagramCamera(
      Point(
        DiagramLimits.bounded(at.x.toDouble - world.x.toDouble * scale).lp,
        DiagramLimits.bounded(at.y.toDouble - world.y.toDouble * scale).lp
      ),
      scale
    ))
    pivot.set(at)
  val zoomIn: Command[Unit, Unit] = scope.action[Unit]("diagram.zoom-in", "Zoom in", idle)(_ => zoom(pivot.get, 1.25))
  val zoomOut: Command[Unit, Unit] = scope.action[Unit]("diagram.zoom-out", "Zoom out", idle)(_ => zoom(pivot.get, 0.8))
  val reset: Command[Unit, Unit] =
    scope.action[Unit]("diagram.reset", "Reset view", idle)(_ => cameraCell.set(DiagramCamera()))
  def moveSelection(by: Point)(using UiTxn): Unit =
    UiTxn.require(!dragging.get, "finish the active gesture first")
    UiTxn.require(DiagramLimits.finite(by), "invalid diagram displacement")
    commit(DiagramGeometry.shift(graph.get, selected.get, by))
  def cancelGesture()(using UiTxn): Unit = finish(cancel = true)
  private[diagram] def begin(event: PointerEvent)(using UiTxn): PointerResponse =
    if !DiagramLimits.finite(event.position) then PointerResponse.Ignore
    else if dragging.get && !event.captured then
      cancelGesture()
      begin(event)
    else if dragging.get then PointerResponse.Handled
    else if event.button == PointerButton.Secondary || panCell.get || event.mods.has(Mods.Alt) then
      gesture.set(Some(DiagramGesture(
        graph.get,
        cameraCell.get,
        event.position,
        event.position,
        selected.get,
        selected.get,
        pan = true,
        button = event.button
      )))
      pivot.set(event.position)
      canvasRef.focus()
      PointerResponse.Capture
    else if event.button != PointerButton.Primary then PointerResponse.Ignore
    else
      event.hit match
        case Hit.Part(part) =>
          val id = part.value
          UiTxn.require(graph.get.index.contains(id), "diagram node no longer exists")
          val before = selected.get
          val next = if event.mods.has(Mods.Shift) then
            if before(id) then before - id else before + id
          else if before(id) then before else Set(id)
          selected.set(next)
          pivot.set(event.position)
          canvasRef.focus()
          if next.isEmpty then PointerResponse.Handled
          else
            gesture.set(Some(DiagramGesture(
              graph.get,
              cameraCell.get,
              event.position,
              event.position,
              next,
              before,
              pan = false,
              button = event.button
            )))
            PointerResponse.Capture
        case Hit.Self =>
          selected.set(Set.empty)
          PointerResponse.Handled
        case Hit.Miss => PointerResponse.Ignore
  private[diagram] def move(at: Point)(using UiTxn): Unit =
    UiTxn.require(DiagramLimits.finite(at), "invalid pointer position")
    gesture.update(_.map(_.copy(pointer = at)))
  private[diagram] def release(event: PointerEvent)(using UiTxn): PointerResponse =
    if gesture.get.exists(_.button != event.button) then PointerResponse.Handled
    else
      move(event.position)
      finish(cancel = false)
      PointerResponse.Release
  private[diagram] def finish(cancel: Boolean)(using UiTxn): Unit = gesture.get.foreach { active =>
    if cancel then selected.set(active.selectionBefore intersect graph.get.index.keySet)
    else
      val preview = active.scene
      if active.pan then cameraCell.set(preview.camera) else commit(preview.graph)
    gesture.set(None)
  }
  private val handlers = new DiagramHandlers(this)
  val surface: View = Element(
    "diagram",
    ElementInputs(
      Signal.const(()),
      scene,
      scene,
      scene,
      Presentation.const(())
    ),
    DiagramSurface,
    focusable = true,
    handlers = Some(handlers)
  ).ref(canvasRef)
  val view: View = DiagramView(this)
