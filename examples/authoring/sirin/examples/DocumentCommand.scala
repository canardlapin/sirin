// A-0 authoring corpus, program 5: a document command shared by a menu item,
// a keyboard shortcut, and a button.
//
// Exercises: snapshot-associated completion (saving revision 17 while the
// user edits revision 18 marks 17 as saved and leaves the document dirty),
// enablement from the command for every binding, a workspace-scoped document
// whose save outlives the window that started it, and a plain function
// returning a View with no scope.
package sirin.examples.document

import sirin.*

trait DocumentStore:
  def write(text: String): Work[Unit]

/** A document owned by a workspace scope, not by any window or editor. */
final class Document(scope: Scope, store: DocumentStore, initial: String):
  val text: Cell[String] = scope.cell(initial)

  // OPEN QUESTION (REVIEW Q6): is the revision of a freshly created cell the
  // same value that `Revision.initial` names?
  private val savedRevision = scope.cell(Revision.initial)
  private val saveError     = scope.cell(Option.empty[String])

  val revision: Signal[Revision] = text.versioned.map(_.revision)
  val dirty: Signal[Boolean]     = (revision, savedRevision).mapN((r, s) => r.isAfter(s))

  /** Save. The snapshot is the versioned text at dispatch; completion is
    * reconciled against that snapshot, never against the current revision.
    *
    * AWKWARD (REVIEW #4): SnapshotAssociated says how a result is accepted,
    * not how invocations interleave. Two concurrent saves can finish out of
    * order and leave revision 17's text on disk under an 18 marker; this
    * needs Serial as well, which the single `freshness` parameter cannot say.
    */
  val save: Command[Unit, Unit] =
    scope.command[Unit, Versioned[String], Unit](
      id = "document.save",
      label = "Save",
      freshness = Freshness.SnapshotAssociated,
      enabled = dirty
    ) { _ =>
      val snapshot = text.versioned.get
      saveError.set(None)
      Job(snapshot, store.write(snapshot.value))
    } { (snapshot, outcome) =>
      outcome match
        case Outcome.Succeeded(()) => savedRevision.update(_.max(snapshot.revision))
        case Outcome.Failed(e)     => saveError.set(Some(s"Revision ${snapshot.revision.value} was not saved: ${e.getMessage}"))
        case Outcome.Cancelled     => ()
    }

  val revert: Command[Unit, Unit] = scope.action("document.revert", "Revert to Saved", enabled = dirty) { _ =>
    // A real revert would reload; the corpus only needs the enablement path.
    UiTxn.reject("revert is not part of this example")
  }

  val status: Signal[String] =
    (revision, savedRevision, save.running).mapN { (r, s, busy) =>
      val saving = if busy then " — saving…" else ""
      if r.isAfter(s) then s"Revision ${r.value}, last saved ${s.value}$saving"
      else s"Saved at revision ${s.value}$saving"
    }

  val error: Signal[Option[String]] = saveError

/** The editor needs no local state, so it is a plain function. */
def editor(doc: Document): View =
  Column(
    Row(
      Button(doc.save).primary, // label, enablement, and busy state from the command
      Button(doc.revert),
      Spacer().grow,
      Text(doc.status)
        .style(TextStyle.Caption)
        .semantics(doc.status.map(s => Semantics(Role.Status, name = Some(s), live = Live.Polite)))
    ).spacing(Spacing.small).align(Alignment.Center),
    Switch(doc.error) {
      case Some(e) => Text(e).color(ColorRole.Danger).semantics(Semantics(Role.Alert, live = Live.Assertive))
      case None    => View.empty
    },
    TextField.bind(doc.text).label("Document").multiline.grow
  ).spacing(Spacing.medium).padding(Spacing.large)

/** The menu item's chord is the shortcut: one declaration, active window-wide. */
def menu(doc: Document): MenuBar =
  MenuBar(
    Menu(
      "File",
      MenuItem(doc.save, Chord.primary('s')),
      MenuEntry.Separator,
      MenuItem(doc.revert)
    )
  )

object SlowDisk extends DocumentStore:
  def write(text: String): Work[Unit] = Work {
    if summon[Cancellation].cancelled then throw new InterruptedException("cancelled")
    require(text.length >= 0)
  }

@main def documentCommand(): Unit =
  Application.run { app =>
    // The document belongs to the workspace: closing the window does not
    // cancel an in-flight save (VISION §5.4 ownership rule).
    val doc   = Document(app.child("document"), SlowDisk, "")
    val title = doc.dirty.map(d => if d then "Untitled — Edited" else "Untitled")
    Seq(Window(title, menu(doc))(editor(doc)))
  }
