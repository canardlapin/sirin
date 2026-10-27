// A-0 authoring corpus, program 3: asynchronous search with stale-result
// handling.
//
// Exercises: a debounce deadline, latest-only freshness (a reply for "ab" that
// arrives after "abc" was issued is dropped), keeping the previous results
// visible but marked stale while a request is pending, cooperative
// cancellation, and selection that survives a new result set by key.
package sirin.examples.search

import scala.concurrent.duration.*

import sirin.*

final case class Book(isbn: String, title: String, author: String)

trait Catalog:
  def search(query: String): Work[Seq[Book]]

/** What the results area shows, derived from the task state. */
final case class ResultsView(books: Seq[Book], status: String, stale: Boolean)

object ResultsView:
  def of(state: TaskState[String, Seq[Book]]): ResultsView =
    val books = state.shown.getOrElse(Nil)
    val status =
      if state.error.isDefined then "Search failed. Try again."
      else if state.pending then "Searching…"
      else state match
        case TaskState.Done(Completed(q, Outcome.Succeeded(_), _)) => s"${books.size} results for “$q”"
        case _ => ""
    ResultsView(books, status, state.stale)

val SearchPanel = Component.withDeps[Catalog, Unit]("SearchPanel") { (catalog, scope, _) =>
  val query    = scope.cell("")
  val selected = scope.cell(Option.empty[String]) // by ISBN, so it survives new results

  // The deadline belongs to the scope: disposing the panel cancels it.
  val settled = scope.debounce(query.map(_.trim), 150.millis)

  // Catalog is a stable dependency; changing it remounts this panel.
  val results: Signal[TaskState[String, Seq[Book]]] =
    scope.latest(settled, LatestPolicy.LatestOnly) { q =>
      if q.isEmpty then Work(Seq.empty[Book]) else catalog.search(q)
    }

  val view  = results.map(ResultsView.of)
  val books = view.map(_.books)

  val open: Command[String, Unit] = scope.action("search.open", "Open book") { isbn =>
    selected.set(Some(isbn))
  }

  def bookRow(book: Inputs[Book]): View =
    Text(book.map(b => s"${b.title} — ${b.author}"))
      .activate(open, book.map(_.isbn), Role.ListItem)
      .semantics((book, selected).mapN((b, s) =>
        Semantics(Role.ListItem, name = Some(b.title), selected = Some(s.contains(b.isbn)))))

  Column(
    TextField.bind(query).label("Search the catalog").placeholder("Title, author, or ISBN"),
    Text(view.map(_.status))
      .style(TextStyle.Caption)
      .semantics(view.map(v => Semantics(Role.Status, name = Some(v.status), busy = v.stale, live = Live.Polite))),
    // While a newer request is pending, previous results stay visible but dimmed.
    Scroll(Column(ForEach(books, key = (b: Book) => b.isbn)(bookRow)).spacing(Spacing.xsmall))
      .semantics(Semantics(Role.List, name = Some("Results")))
  ).spacing(Spacing.medium).padding(Spacing.large)
}

/** A slow fake catalog that honors cancellation. */
final class SlowCatalog(books: Seq[Book]) extends Catalog:
  def search(query: String): Work[Seq[Book]] = Work {
    val c = summon[Cancellation]
    val q = query.toLowerCase
    books.filter { b =>
      if c.cancelled then throw new InterruptedException("superseded")
      b.title.toLowerCase.contains(q) || b.author.toLowerCase.contains(q) || b.isbn == query
    }
  }

@main def asyncSearch(): Unit =
  val catalog = SlowCatalog(Seq(Book("9780262046305", "Elements of Causal Inference", "Peters et al.")))
  Application.run(_ => Windows.single("catalog")(_ => Window("Catalog", MenuBar.empty)(SearchPanel.bind(catalog).view)))
