package sirin.tooling

import sirin.*
import sirin.engine.*

/** An ordinary public-control tool window. Only drain crosses into owner runtime diagnostics. */
final class DeveloperInspector(val runtime: HeadlessRuntime)(application: runtime.OwnedScope):
  private val owner = application.child("developer-inspector")
  private val keys = owner.keyed(Seq.empty[String])(identity)
  private val focus = owner.ref()
  private var pending = false
  private var serial = 0L
  val data = owner.cell(Option.empty[InspectorSnapshot])(using
    (a, b) => a.map(_.serial) == b.map(_.serial))
  val section = owner.cell("Commands")
  val query = owner.cell("")
  val page = owner.cell(0)
  val selected = owner.cell(Option.empty[String])
  val includeValues = owner.cell(false)
  val budget = owner.cell(512)
  private def request()(using UiTxn): Unit = owner.afterCommit(() => pending = true)
  val open = owner.action[Unit]("developer.inspector.open", "Inspector") { _ =>
    if keys.get("inspector").isEmpty then keys.append("inspector")
    request()
    focus.focus()
  }
  val refresh = owner.action[Unit]("developer.inspector.refresh", "Refresh")(_ => request())
  val more = owner.action[Unit]("developer.inspector.more", "Capture more", budget.map(_ < 8192)) { _ =>
    budget.update(value => math.min(value * 2, 8192))
    request()
  }
  private def hide()(using UiTxn): Unit =
    if keys.get("inspector").nonEmpty then keys.remove("inspector")
    data.set(None)
    selected.set(None)
    query.set("")
    owner.afterCommit(() => pending = false)
  val close = owner.action[Unit]("developer.inspector.close", "Close inspector")(_ => hide())
  val choose = owner.action[String]("developer.inspector.section", "Section") { next =>
    UiTxn.require(InspectorData.sections.contains(next), "unknown inspector section")
    section.set(next)
    selected.set(None)
    query.set("")
    page.set(0)
  }
  val navigate = owner.action[InspectorLink]("developer.inspector.navigate", "Inspect") { link =>
    UiTxn.require(InspectorData.sections.contains(link.section), "unknown inspector section")
    section.set(link.section)
    selected.set(Some(link.key))
    query.set("")
    page.set(0)
  }
  val rows = data.zip(section).map { (snapshot, tab) =>
    snapshot.fold(Vector.empty[InspectorRow])(InspectorData.rows(_, tab))
  }
  val filtered = rows.zip(query).map { (items, text) =>
    val needle = text.trim.toLowerCase
    items.filter(row => row.label.toLowerCase.contains(needle) || row.detail.toLowerCase.contains(needle))
  }
  val currentPage = filtered.zip(page).map((items, n) => math.max(0, math.min(n, (items.size - 1) / 5)))
  val visibleRows = filtered.zip(currentPage).map((items, n) => items.slice(n * 5, n * 5 + 5))
  val selectedRow = rows.zip(selected).map((items, id) => id.flatMap(key => items.find(_.key == key)))
  val detail = selectedRow.zip(selected).map {
    case (Some(row), _) => row.detail
    case (None, Some(id)) => s"Record $id is outside this snapshot or has retired. Refresh or capture more rows."
    case _ => "Select a record to inspect its dependencies and causes."
  }
  val links = selectedRow.map(_.fold(Vector.empty[InspectorLink])(_.links))
  val previous = owner.action[Unit]("developer.inspector.previous", "Previous", currentPage.map(_ > 0)) { _ =>
    page.set(currentPage.get - 1)
  }
  val next = owner.action[Unit]("developer.inspector.next", "Next",
    currentPage.zip(filtered).map((n, items) => (n + 1) * 5 < items.size)) { _ => page.set(currentPage.get + 1) }
  private val summary = data.zip(section).map { (snapshot, tab) =>
    snapshot.fold("Choose Refresh to capture committed state.") { sample =>
      tab + " · " + InspectorData.summary(sample, tab)
    }
  }
  private val pageLabel = currentPage.zip(filtered).map { (n, items) =>
    s"Page ${n + 1} of ${math.max(1, (items.size + 4) / 5)} · ${items.size} matches"
  }
  private val results = Column(
    ForEach(visibleRows.map(value => value: Seq[InspectorRow]), (row: InspectorRow) => row.key) { input =>
      Button(input.map(_.label)).singleLine.onPress { selected.set(Some(input.get.key)) }
    },
    Text(selectedRow.map(_.fold("Details")(_.label))).style(TextStyle.Heading),
    Text(detail),
    ForEach(links.map(value => value: Seq[InspectorLink]), (link: InspectorLink) => link.section + ":" + link.key) {
      input => Button(input.map(_.label)).onPress { navigate.dispatch(input.get) }
    }
  ).spacing(8.lp)
  private val content = Column(
    Row(Text("Sirin Inspector").style(TextStyle.Heading).grow,
      Button(refresh).ref(focus), Button(more), Button(close)).spacing(8.lp),
    Checkbox(Inputs.from(includeValues), Signal.const("Show scalar values")) { value =>
      includeValues.set(value)
      request()
    },
    Text(summary),
    Row(InspectorData.sections.map(name => Button(name).command(choose, Signal.const(name)))*).spacing(6.lp),
    TextField.bind(query).label("Search captured records").maxLength(128),
    Row(Button(previous), Text(pageLabel).grow, Button(next)).spacing(8.lp),
    Scroll(results).grow
  ).spacing(8.lp).padding(12.lp)
  val windows: Windows = Windows(keys) { (_, _) =>
    Window("Sirin Inspector", MenuBar.empty)(content).onClose { hide() }
  }.menuBar(MenuBar(Menu("Developer", MenuItem(open, Chord(Key.Character('i'), Mods.Primary | Mods.Alt)))))
  def attach(application: Windows): Windows = Windows.withTool(application, windows)
  private def read[A](signal: Signal[A]): A = runtime.read(signal) match
    case Value.Ok(value) => value
    case Value.Failed(error) => throw error
  /** Called between transactions, once after each owner loop. No request means no inspection or capture work. */
  def drain(windowData: => Vector[InspectedWindow]): Boolean =
    if !pending then false
    else
      pending = false
      if owner.isDisposed || read(keys.snapshot).isEmpty then false
      else
        val count = read(budget)
        val snapshot = runtime.inspect(
          InspectionLimits(scopes = count, nodes = count, edges = math.min(8192, count * 2), commands = count),
          includeValues = read(includeValues))
        serial += 1
        val next = InspectorSnapshot(snapshot, windowData.take(16), serial)
        runtime.transact { data.set(Some(next)) } match
          case CommitResult.Committed(_) => true
          case other => throw new IllegalStateException(s"inspector publication failed: $other")
