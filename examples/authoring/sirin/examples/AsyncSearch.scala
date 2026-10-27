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
  def of(state: TaskState[(String, Catalog), Seq[Book]]): ResultsView = state match
    case TaskState.Idle =>
      ResultsView(Nil, "", stale = false)
    case TaskState.Pending((q, _), previous) =>
      val kept = previous.collect { case Completed(_, Outcome.Succeeded(bs), _) => bs }.getOrElse(Nil)
      ResultsView(kept, s"Searching for “$q”…", stale = kept.nonEmpty)
    case TaskState.Done(Completed((q, _), Outcome.Succeeded(bs), current)) =>
      // `current` is always true under LatestOnly; it can be false under Progressive.
      val count = if bs.isEmpty then "No results" else s"${bs.size} results"
      ResultsView(bs, s"$count for “$q”", stale = !current)
    case TaskState.Done(Completed(_, Outcome.Failed(e), _)) =>
      ResultsView(Nil, s"Search failed: ${e.getMessage}", stale = false)
    case TaskState.Done(Completed(_, Outcome.Cancelled, _)) =>
      ResultsView(Nil, "", stale = false)

val SearchPanel: Component[Catalog] = Component("SearchPanel") { (scope, catalog) =>
  val query    = scope.cell("")
  val selected = scope.cell(Option.empty[String]) // by ISBN, so it survives new results

  // The deadline belongs to the scope: disposing the panel cancels it.
  val settled = scope.debounce(query.map(_.trim), 150.millis)

  // The catalog is part of the input: a different catalog is a new request.
  val results: Signal[TaskState[(String, Catalog), Seq[Book]]] =
    scope.latest(settled.zip(catalog), Freshness.LatestOnly) { (q, c) =>
      if q.isEmpty then Work(Seq.empty[Book]) else c.search(q)
    }

  val view  = results.map(ResultsView.of)
  val books = view.map(_.books)

  val open: Command[String, Unit] = scope.action("search.open", "Open book") { isbn =>
    selected.set(Some(isbn))
  }

  // AWKWARD (REVIEW #8): a plain View has no activation modifier, so a
  // selectable row is a Button, and its list-item role has to be restated.
  def bookRow(book: Book): View =
    Button(s"${book.title} — ${book.author}")
      .command(open, Signal.const(book.isbn))
      .semantics(selected.map(s => Semantics(Role.ListItem, name = Some(book.title), selected = Some(s.contains(book.isbn)))))

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
  Application.run(_ => Seq(Window("Catalog", MenuBar.empty)(SearchPanel(catalog))))
