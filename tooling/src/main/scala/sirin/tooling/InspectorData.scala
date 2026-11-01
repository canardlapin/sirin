package sirin.tooling

import sirin.*
import sirin.engine.*

final case class InspectedFrame(
    generation: Long, width: Int, height: Int, workerNanos: Long, queueNanos: Long,
    programBytes: Long, readbackBytes: Long, tiles: Int, path: String, cache: String):
  require(path.length <= 64 && cache.length <= 1024, "frame diagnostic text budget")
final case class InspectedWindow(
    id: Long, title: String, requested: Option[Long], presented: Option[Long],
    ready: Boolean, pendingInputs: Int, presentations: Long, staleInputs: Long,
    frame: Option[InspectedFrame], failures: Vector[WindowRenderFailure]):
  require(title.length <= 128 && failures.size <= 8, "window diagnostic budget")
final case class InspectorSnapshot(runtime: RuntimeInspection, windows: Vector[InspectedWindow], serial: Long)
final case class InspectorLink(section: String, key: String, label: String)
final case class InspectorRow(key: String, label: String, detail: String, links: Vector[InspectorLink] = Vector.empty)

object InspectorData:
  val sections = Vector("Commands", "Signals", "Scopes", "Work", "Frames", "Causes")
  def clip(value: String, limit: Int = 1024): String =
    val end = math.min(value.length, limit)
    val safe =
      if end > 0 && end < value.length && Character.isHighSurrogate(value.charAt(end - 1)) then end - 1 else end
    new String(value.substring(0, safe).toCharArray)
  /** Preserve diagnostic newlines; escape invalid UTF-16 and controls before native text shaping. */
  private def display(value: String): String =
    val result = new StringBuilder
    var index = 0
    while index < value.length do
      val c = value.charAt(index)
      if Character.isHighSurrogate(c) && index + 1 < value.length &&
          Character.isLowSurrogate(value.charAt(index + 1)) then
        result.append(c)
        result.append(value.charAt(index + 1))
        index += 2
      else
        if Character.isSurrogate(c) || (Character.isISOControl(c) && c != '\n') then
          val hex = Integer.toHexString(c.toInt)
          result.append("\\" + "u" + "0" * (4 - hex.length) + hex)
        else if c == '\\' then result.append("\\\\")
        else result.append(c)
        index += 1
    result.toString
  private def signal(id: Long): InspectorLink = InspectorLink("Signals", id.toString, s"Signal $id")
  private def scope(id: Long): InspectorLink = InspectorLink("Scopes", id.toString, s"Scope $id")
  private def cause(id: Long): InspectorLink = InspectorLink("Causes", id.toString, s"Cause $id")
  def rows(snapshot: InspectorSnapshot, section: String): Vector[InspectorRow] =
    val r = snapshot.runtime
    val result = section match
      case "Commands" => r.commands.rows.map { c =>
        InspectorRow(c.node.toString, s"${c.name} · owner ${c.owner} · running ${c.running.fold("?")(_.toString)}",
          s"${c.label}\nEnabled: ${c.enabled.fold("not materialized")(_.toString)}\n" +
            s"Refusal: ${c.refusal.getOrElse("None")}\nOwner: ${c.owner}",
          Vector(scope(c.owner)) ++ c.enabledNode.map(signal))
      }
      case "Signals" =>
        val inputs = r.edges.rows.groupMap(_.node)(_.input)
        r.nodes.rows.map { n =>
          val dependencies = inputs.getOrElse(n.id, Vector.empty).distinct
          InspectorRow(n.id.toString, s"${n.id} · ${n.kind} · ${n.state} · cell ${n.cell}",
            s"Value: ${n.preview.getOrElse("Hidden or opaque")}\nRevision: ${n.revision}\n" +
              s"Evaluations: ${n.evaluations}\nChanged by: ${n.changedBy}\n" +
              s"Captured inputs: ${dependencies.take(16).mkString(", ")} (showing up to 16 links)",
            (n.owner.map(scope).toVector ++ n.changedBy.map(cause) ++ dependencies.take(16).map(signal)).take(18))
        }
      case "Scopes" => r.scopes.rows.map { s =>
        InspectorRow(s.id.toString, s"${s.id} · ${s.name}",
          s"Parent: ${s.parent}\nPosition: ${s.position}\nDefinition: ${s.definition.getOrElse("None")}",
          s.parent.map(scope).toVector)
      }
      case "Work" => r.work.rows.map { w =>
        InspectorRow(w.id.toString, s"Work ${w.id} · ready ${w.ready} · started ${w.started}",
          s"Owner has result: ${w.completed}\nCancellation requested: ${w.cancelled}\n" +
            s"Worker transport: ${r.workers}\nInspection does not collect pending results.")
      }
      case "Frames" => snapshot.windows.map { w =>
        val frame = w.frame.fold("No rendered result.") { f =>
          s"Rendered: ${f.generation} · ${f.width}×${f.height}\nPath: ${f.path}\n" +
            f"Worker: ${f.workerNanos / 1e6}%.3f ms · queue: ${f.queueNanos / 1e6}%.3f ms\n" +
            s"Program bytes: ${f.programBytes} · readback bytes: ${f.readbackBytes} · tiles: ${f.tiles}\n" +
            s"Cache at render: ${f.cache}"
        }
        InspectorRow(w.id.toString, s"${w.id} · ${clip(w.title, 128)}",
          s"Requested: ${w.requested} · presented: ${w.presented}\nInput ready: ${w.ready}\n" +
            s"Pending inputs: ${w.pendingInputs} · stale inputs: ${w.staleInputs}\n" +
            s"Presentations: ${w.presentations}\n$frame\n" +
            w.failures.map(f => s"Failure ${f.generation}: ${f.category}: ${f.detail}").mkString("\n"))
      }
      case "Causes" => r.traces.rows.reverse.map { t =>
        val failure = r.failures.rows.find(_.cause == t.id).map(_.detail).getOrElse("None retained")
        InspectorRow(t.id.toString, s"${t.id} · ${t.cause} · ${t.outcome}",
          s"Parent: ${t.parent}\nGraph passes: ${t.graphPasses}\nStructural passes: ${t.structuralPasses}\n" +
            s"Failure: $failure\nMissing parents may have aged out of retained history.", t.parent.map(cause).toVector)
      }
      case _ => Vector.empty
    result.map { row =>
      val safe = display(row.detail)
      val detail = if safe.length <= 4096 then safe else clip(safe, 4070) + "\n[detail truncated]"
      row.copy(label = clip(display(row.label), 256), detail = detail)
    }
  def summary(snapshot: InspectorSnapshot, section: String): String =
    val r = snapshot.runtime
    val count = section match
      case "Commands" => (r.commands.rows.size, r.commands.omitted)
      case "Signals" => (r.nodes.rows.size, r.nodes.omitted)
      case "Scopes" => (r.scopes.rows.size, r.scopes.omitted)
      case "Work" => (r.work.rows.size, r.work.omitted)
      case "Frames" => (snapshot.windows.size, 0)
      case _ => (r.traces.rows.size, r.traces.omitted)
    s"Snapshot ${snapshot.serial} · captured ${count._1} · omitted ${count._2} · " +
      s"omitted input edges ${r.edges.omitted}"
