// Application descriptions and backend-neutral launch service.
package sirin

enum MenuEntry:
  case Item(command: Command[Unit, ?], chord: Option[Chord])
  case Separator
  case Submenu(menu: Menu)

object MenuItem:
  /** A menu item whose label and enablement come from the command. A chord
    * given here is also an active window-wide shortcut.
    */
  def apply(command: Command[Unit, ?]): MenuEntry              = MenuEntry.Item(command, None)
  def apply(command: Command[Unit, ?], chord: Chord): MenuEntry = MenuEntry.Item(command, Some(chord))

final case class Menu(title: String, entries: MenuEntry*)

final case class MenuBar(menus: Menu*)

object MenuBar:
  val empty: MenuBar = MenuBar()

enum CloseDecision:
  case Allow, KeepOpen

sealed trait Window:
  /** Runs transactionally before dismissal. Refusal or failure keeps the window. */
  def onClose(run: Action): Window
  /** KeepOpen commits prompt/error state without dismissing the window.
    * A confirmed asynchronous close can remove its key from the window source.
    */
  def onCloseRequest(run: UiTxn ?=> CloseDecision): Window

/** Window builders run once per key mount, with independent window scopes.
  * Source removal disposes that window, not the application/document owner.
  */
sealed trait Windows:
  private[sirin] def view: View

object Windows:
  private final case class Description(view: View) extends Windows

  def apply[K, A](source: CollectionSource[K, A])(build: (Scope, Inputs[A]) => Window)(
      using equality: Equality[A], keySchema: SchemaId[K], valueSchema: SchemaId[A]
  ): Windows =
    val root = Component[Unit]("windows") { (owner, _) =>
      val dismissalEquality: Equality[(K, Revision)] = (a, b) => a._1 == b._1 && a._2 == b._2
      val dismissed = owner.keyed(Seq.empty[(K, Revision)])(_._1)(using dismissalEquality)
      owner.on(source.changes) { changes =>
        changes.deltas.foreach {
          case Delta.Removed(key, _) => dismissed.get(key).foreach { (_, revision) =>
            if changes.to.isAfter(revision) then dismissed.remove(key)
          }
          case _ => ()
        }
      }
      val pairEquality: Equality[(K, A)] = (a, b) => a._1 == b._1 && equality.same(a._2, b._2)
      val row = Component[(K, A)]("window") { (scope, input) =>
        val window = build(scope, Inputs.from(input.map(_._2))) match
          case description: Window.Description => description
        window.copy(prepareDismiss = Some(txn => {
          // Resolve identity before a user action can remove the row or owner.
          val key = input.attempt.get(using txn) match
            case Value.Ok(pair) => pair._1
            case Value.Failed(_) => UiTxn.refuse("window is no longer mounted")(using txn)
          val revision = source.revision.get(using txn)
          nextTxn =>
            if dismissed.get(key)(using nextTxn).isEmpty then dismissed.append((key, revision))(using nextTxn)
            else dismissed.replace((key, revision))(using nextTxn)
        }))
      }(using pairEquality)
      val visible: Signal[Seq[(K, A)]] = source.keyedSnapshot.zip(dismissed.snapshot).map {
        (entries, hidden) => entries.filterNot(pair => hidden.exists(_._1 == pair._1))
      }(using (a, b) => a.size == b.size && a.zip(b).forall((x, y) => pairEquality.same(x, y)))
      ForEach.Description(visible, (pair: (K, A)) => pair._1, input => row(input),
        pairEquality, s"windows:${keySchema.value}:${valueSchema.value}")
    }
    Description(root.view)

  /** Static convenience still creates keyed window ownership, not a second lifecycle. */
  def single(key: String)(build: Scope => Window): Windows =
    val root = Component[Unit]("single-window") { (owner, _) =>
      val source = owner.keyed(Seq(key))(identity)
      apply(source)((scope, _) => build(scope)).view
    }
    Description(root.view)

object Window:
  private[sirin] final case class Description(title: Signal[String], menu: MenuBar,
      content: View, close: Option[UiTxn => CloseDecision] = None,
      prepareDismiss: Option[UiTxn => (UiTxn => Unit)] = None) extends Window with View:
    def onClose(run: Action): Window = onCloseRequest { run; CloseDecision.Allow }
    def onCloseRequest(run: UiTxn ?=> CloseDecision): Window = copy(close = Some(txn => run(using txn)))

  def apply(title: String, menu: MenuBar)(content: View): Window =
    Description(Signal.const(title), menu, content)
  def apply(title: Signal[String], menu: MenuBar)(content: View): Window =
    Description(title, menu, content)

/** Backend SPI. An application includes one platform provider on its classpath. */
trait ApplicationPlatform:
  def run(build: Scope => Windows): Unit

object Application:
  private[sirin] def platform(providers: java.util.Iterator[ApplicationPlatform]): ApplicationPlatform =
    if !providers.hasNext then throw new IllegalStateException("No Sirin application backend is installed")
    val result = providers.next()
    if providers.hasNext then throw new IllegalStateException("Multiple Sirin application backends are installed")
    result

  /** Runs the event loop on the process main thread. `build` receives the
    * application scope, which outlives every window.
    */
  def run(build: Scope => Windows): Unit =
    platform(java.util.ServiceLoader.load(classOf[ApplicationPlatform]).iterator()).run(build)
