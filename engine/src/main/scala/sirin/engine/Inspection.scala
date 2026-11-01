package sirin.engine

/** Output budgets, independent of application graph size. */
final case class InspectionLimits(
    scopes: Int = 128,
    nodes: Int = 512,
    edges: Int = 1024,
    commands: Int = 128,
    work: Int = 128,
    traces: Int = 128,
    text: Int = 128
):
  private val collections = Vector(scopes, nodes, edges, commands, work, traces)
  require(collections.forall(n => n >= 0 && n <= 8192), "inspection collection limit must be between 0 and 8192")
  require(text >= 0 && text <= 1024, "inspection text limit must be between 0 and 1024")

final case class InspectionPage[A](rows: Vector[A], total: Int):
  def omitted: Int = total - rows.size

final case class InspectedScope(
    id: Long, parent: Option[Long], name: String, position: Option[Int], definition: Option[String])
final case class InspectedNode(
    id: Long, kind: String, cell: Option[Long], owner: Option[Long], revision: Option[Long],
    state: String, preview: Option[String], evaluations: Long, changedBy: Option[Long])
final case class InspectedEdge(node: Long, input: Long)
final case class InspectedCommand(
    node: Long, owner: Long, name: String, label: String, enabledNode: Option[Long], running: Option[Int],
    enabled: Option[Boolean], refusal: Option[String])
final case class InspectedWork(id: Long, ready: Boolean, started: Boolean, completed: Boolean, cancelled: Boolean)
final case class InspectionFailure(cause: Long, outcome: TraceOutcome, detail: String)

/** Immutable value data only. Keeping this after runtime retirement cannot keep an application object alive. */
final case class RuntimeInspection(
    scopes: InspectionPage[InspectedScope],
    nodes: InspectionPage[InspectedNode],
    edges: InspectionPage[InspectedEdge],
    commands: InspectionPage[InspectedCommand],
    work: InspectionPage[InspectedWork],
    workers: Option[WorkerPoolSnapshot],
    traces: InspectionPage[TraceRecord],
    failures: InspectionPage[InspectionFailure],
    driver: DriverSnapshot,
    valuesIncluded: Boolean
):
  /** Dependency totals cover the selected nodes, not omitted nodes. */
  def text: String =
    val lines = Vector.newBuilder[String]
    lines += s"Runtime inspection (scalar previews=$valuesIncluded)"
    def section[A](name: String, page: InspectionPage[A])(row: A => String): Unit =
      lines += s"$name: shown=${page.rows.size} total=${page.total} omitted=${page.omitted}"
      page.rows.foreach(value => lines += row(value))
    section("Scopes", scopes)(s => s"scope ${s.id} parent=${s.parent} name=${InspectionText.line(s.name)} " +
      s"position=${s.position} definition=${s.definition.map(InspectionText.line)}")
    section("Signals", nodes)(n => s"signal ${n.id} ${n.kind} cell=${n.cell} owner=${n.owner} " +
      s"revision=${n.revision} state=${n.state} value=${n.preview.map(InspectionText.line)} " +
      s"evaluations=${n.evaluations} changedBy=${n.changedBy}")
    section("Dependencies of selected signals", edges)(e => s"signal ${e.node} <- ${e.input}")
    section("Commands", commands)(c => s"command ${InspectionText.line(c.name)} owner=${c.owner} node=${c.node} " +
      s"enabledNode=${c.enabledNode} running=${c.running} enabled=${c.enabled} " +
      s"refusal=${c.refusal.map(InspectionText.line)}")
    section("Work", work)(w => s"work ${w.id} ready=${w.ready} started=${w.started} " +
      s"completed=${w.completed} cancelled=${w.cancelled}")
    section("Retained traces", traces)(t => s"cause ${t.id} parent=${t.parent} ${t.cause} ${t.outcome} " +
      s"graphPasses=${t.graphPasses} structuralPasses=${t.structuralPasses}")
    section("Retained failures", failures)(f => s"failure ${f.cause} ${f.outcome} ${InspectionText.line(f.detail)}")
    lines += s"workers=$workers"
    lines += s"driver=$driver"
    lines += "Change causes describe committed values; missing cause parents may be outside retained history."
    lines.result().mkString("\n")

private[engine] object InspectionText:
  def clip(value: String, limit: Int): String =
    val end = math.min(value.length, limit)
    val safe =
      if end > 0 && end < value.length && Character.isHighSurrogate(value.charAt(end - 1)) then end - 1 else end
    // Detach even on runtimes where a substring can share a larger source buffer.
    new String(value.substring(0, safe).toCharArray)
  def line(value: String): String = value.flatMap {
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case c if Character.isISOControl(c) => "?"
    case c => c.toString
  }
  def scalar(value: Any, limit: Int): Option[String] =
    val text = value match
      case null => Some("null")
      case value: String => Some(value)
      case value: Boolean => Some(value.toString)
      case value: Byte => Some(value.toString)
      case value: Short => Some(value.toString)
      case value: Int => Some(value.toString)
      case value: Long => Some(value.toString)
      case value: Float => Some(value.toString)
      case value: Double => Some(value.toString)
      case value: Char => Some(value.toString)
      case _ => None
    text.map(clip(_, limit))
