package reviewpad

import java.nio.file.Path
import sirin.*

/** Independent consumer: no engine, host, desktop, specimen or private API imports. */
object ReviewPad:
  def main(args: Array[String]): Unit =
    val file = args.toVector match
      case Vector("--file", value) => Path.of(value)
      case Vector() => Path.of(System.getProperty("user.home"), ".reviewpad", "review.note")
      case _ => throw new IllegalArgumentException("Usage: ReviewPad [--file PATH]")
    val store = new NoteStore(file)
    Application.run { (scope: Scope, services: ApplicationServices) =>
      new Workspace(scope, services, store).windows
    }

final class Workspace(scope: Scope, services: ApplicationServices, store: NoteStore):
  private val loaded = scope.cell(false)
  private val title = scope.cell(Note.empty.title)
  private val body = scope.cell(Note.empty.body)
  private val reviewed = scope.cell(false)
  private val saved = scope.cell(Option.empty[Note])
  private val error = scope.cell(Option.empty[String])
  private val prompt = scope.cell(false)
  private val discard = scope.cell(false)
  private val quitAnchor = scope.ref()
  private val keepEditing = scope.ref()
  private val note = (title, body, reviewed).mapN(Note.apply)
  private val problem = note.map(Note.problem)
  private val dirty = note.zip(saved).map((current, stored) => !stored.contains(current))

  private val load = scope.command[Unit, Unit, Loaded]("note.open", "Retry opening",
    WorkPolicy(Concurrency.RefuseWhileRunning, Acceptance.EveryInvocation),
    status => loaded.zip(status.running).map((ready, running) => !ready && running == 0)) { _ =>
    error.set(None)
    Job((), store.load)
  } { (_, result) =>
    result.outcome match
      case Outcome.Succeeded(value) =>
        title.set(value.note.title)
        body.set(value.note.body)
        reviewed.set(value.note.reviewed)
        saved.set(Option.when(value.exists)(value.note))
        loaded.set(true)
      case Outcome.Failed(failure) => error.set(Some(message(failure)))
      case Outcome.Cancelled => ()
  }
  private val save = scope.command[Unit, Note, Unit]("note.save", "Save", WorkPolicy.save(Capacity(2)),
    _ => (loaded, dirty, problem).mapN((ready, changed, invalid) => ready && changed && invalid.isEmpty)) { _ =>
    val snapshot = note.get
    error.set(None)
    Job(snapshot, store.save(snapshot))
  } { (snapshot, result) =>
    result.outcome match
      case Outcome.Succeeded(_) => saved.set(Some(snapshot))
      case Outcome.Failed(failure) => error.set(Some(message(failure)))
      case Outcome.Cancelled => ()
  }
  scope.effect(Signal.const(())) { _ =>
    load.dispatch(())
    ()
  }
  private def message(failure: Throwable): String =
    Option(failure.getMessage).getOrElse("The note operation failed.").take(512)
  private val status = (loaded, dirty, save.running).mapN { (ready, changed, running) =>
    if !ready then "Opening note…"
    else if running > 0 then "Saving… You can keep editing."
    else if changed then "Unsaved changes"
    else "Saved"
  }
  private val cancel = scope.action[Unit]("quit.cancel", "Keep editing") { _ => prompt.set(false) }
  private val confirm = scope.action[Unit]("quit.discard", "Discard and quit",
    enabled = save.running.map(_ == 0)) { _ =>
    discard.set(true)
    services.quit.dispatch(())
    ()
  }
  private val promptText = save.running.map { running =>
    if running > 0 then "A save is still running. Wait for it to finish before quitting."
    else "Discard the unsaved changes to this note and quit?"
  }
  private val content = Column(
    Row(Text("ReviewPad").style(TextStyle.Title).grow, Button(services.quit).ref(quitAnchor)),
    Switch(loaded) {
      case false => Column(Text("Opening your note"), Show(error.map(_.nonEmpty))(Button(load))).spacing(12.lp)
      case true => Column(
        TextField.bind(title).maxLength(256).label("Title").error(problem),
        TextField.bind(body).maxLength(16384).label("Notes").multiline.grow,
        Checkbox.bind(reviewed, "Review complete"),
        Row(Button(save).primary, Text(status).grow).spacing(12.lp)
      ).spacing(12.lp).grow
    }.grow,
    Show(error.map(_.nonEmpty))(
      Text(error.map(_.getOrElse(""))).color(ColorRole.Danger)
        .semantics(Semantics(Role.Alert, live = Live.Assertive))
    ),
    Popup(prompt, quitAnchor, placement = Placement.Over, initialFocus = Some(keepEditing),
      dismissal = Dismissal(onEscape = true, onOutsidePress = false), modal = true)(
      Column(Text("Quit ReviewPad?").style(TextStyle.Heading), Text(promptText),
        Row(Button(cancel).ref(keepEditing), Button(confirm)).spacing(12.lp)
      ).spacing(12.lp).padding(20.lp).width(420.lp)
        .semantics(Semantics(Role.Dialog, name = Some("Quit ReviewPad?")))
    )
  ).spacing(16.lp).padding(20.lp)

  val windows: Windows = Windows.single("review") { _ =>
    Window(title.map(_ + " — ReviewPad"), MenuBar.empty)(content).onCloseRequest {
      services.quit.dispatch(())
      CloseDecision.KeepOpen
    }
  }.menuBar(MenuBar(Menu("File", MenuItem(save, Chord.primary('s')),
    MenuItem(services.quit, Chord.primary('q'))))).onQuitRequest {
    if discard.get || (save.running.get == 0 && (!loaded.get || !dirty.get)) then CloseDecision.Allow
    else
      prompt.set(true)
      CloseDecision.KeepOpen
  }
