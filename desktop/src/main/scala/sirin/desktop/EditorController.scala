package sirin.desktop

import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.{WordKind, UnicodeAnalysis}

/** Owner-confined input policy over presented geometry: focus, IME composition, staged editing services and
  * accessibility actions for public elements. Each delivered event runs in its own transaction.
  */
final class EditorController(
    runtime: HeadlessRuntime,
    analyze: String => UnicodeAnalysis,
    readClipboard: () => String,
    writeClipboard: String => Unit,
    clearComposition: () => Unit
):
  val interaction = new Interaction
  val elements = new ElementInput(runtime, interaction)
  private var captured = CapturedTree(Node(NodeId(Vector("empty")), Content.Empty), Map.empty)
  private var shown = Option.empty[(Long, LayoutResult)]
  private var refusals = 0
  private var composingElement = Option.empty[Long]
  var notice = Option.empty[String]
  def rejected: Int = refusals
  def focusedElement: Option[Long] = interaction
    .focused
    .flatMap(
      _.kind match
        case TargetKind.Custom(frame) =>
          Some(frame.instance)
        case _ =>
          None
    )
  def tree: Node = captured.tree

  /** At most 512 UTF-16 units, never ending in half a surrogate pair. */
  private def bounded(message: String): String =
    val clipped = message.take(512)
    if message.length > 512 && clipped.lastOption.exists(Character.isHighSurrogate) then
      clipped.dropRight(1)
    else
      clipped
  private def reject(message: String): Unit =
    refusals += 1
    notice = Some(bounded(message))
  private def boundaries(s: String): Vector[Int] =
    val a = analyze(s)
    a.graphemeBoundaries.map(o => a.source.utf16Offset(o).get.value)

  /** Effects wait for the handler's transaction; a refused or failed edit leaves the clipboard and IME alone. */
  private final class StagedServices extends TextEditingServices:
    var copied = Option.empty[String]
    var cancelled = false
    var refusal = Option.empty[String]
    def graphemeBoundaries(text: String): Vector[Int] = boundaries(text)
    def words(text: String): Vector[TextRange] =
      val a = analyze(text)
      a.words
        .filter(_.kind != WordKind.NonWord)
        .map(w => TextRange(a.source.utf16Offset(w.range.start).get.value, a.source.utf16Offset(w.range.end).get.value))
    def clipboard: Option[String] =
      try
        Some(readClipboard())
      catch
        case _: NativeWindow.ClipboardUnavailable =>
          None
    def copy(text: String): Unit = copied = Some(text)
    def cancelComposition(): Unit = cancelled = true
    def presented(request: TextRequest): Option[TextLayout] = elements.presented(request)
    def presentedSize: Option[Size] = None
    def refused(reason: String): Unit = refusal = Some(reason)
  private def staged[A](run: TextEditingServices => Option[CommitResult[A]]): Option[CommitResult[A]] =
    val services = new StagedServices
    val result = run(services)
    result match
      case Some(CommitResult.Committed(_)) =>
        services.copied.foreach(writeClipboard)
        if services.cancelled then
          composingElement = None
          clearComposition()
        services.refusal.foreach(reject)
      case _ =>
        ()
    result
  def textElement: Option[(Long, Placed, TextInputRequest)] = elements.textTarget
  def composingText: Boolean = composingElement.nonEmpty

  /** Delivers one text-channel event; a refused composition update also retires the platform's marked text. */
  private def elementText(event: TextInputEvent): Unit =
    val before = refusals
    val result = staged(services => elements.text(event, services))
    result.foreach { r =>
      val target = textElement.map(_._1)
      r match
        case CommitResult.Committed(handled) =>
          cleared(before, handled)
          event match
            case TextInputEvent.Compose(text, _) if text.nonEmpty =>
              composingElement = target
            case TextInputEvent.Compose(_, _) | TextInputEvent.Commit(_) | TextInputEvent.CancelComposition =>
              composingElement = None
            case _ =>
              ()
        case _ =>
          reject("This text input could not be committed.")
          if composingElement.nonEmpty then
            retireElementComposition()
    }

  /** A handled event that reported no refusal clears the previous notice, as a successful edit does. */
  private def cleared(before: Int, handled: Boolean): Unit =
    if handled && refusals == before then
      notice = None

  /** Ends any element composition, telling the element when it is still mounted and presented. */
  private def retireElementComposition(): Unit =
    composingElement.foreach { id =>
      composingElement = None
      clearComposition()
      elements.deliver(id, TextInputEvent.CancelComposition, TextEditingServices.unavailable)
    }
  private def retireMovedComposition(): Unit =
    if composingElement.exists(id => !textElement.exists(_._1 == id)) then
      retireElementComposition()
  def update(value: CapturedTree): Unit =
    require(
      value.elements.values.forall(_.capacity == Capacity(256)),
      "this desktop host supports the shared 256-event input capacity only"
    )
    captured = value
    elements.update(value.elements)
    if composingElement.exists(id => !value.elements.contains(id)) then
      composingElement = None
      clearComposition()
  def install(revision: Long, layout: LayoutResult): Unit =
    shown = Some((revision, layout))
    interaction.install(InteractionSnapshot(revision, layout))
    elements.install(layout)
    // A control that dropped its composition by itself, e.g. after external replacement, ends the platform's.
    if composingElement.exists(id => textElement.exists((target, _, request) => target == id && !request.composing))
    then
      composingElement = None
      clearComposition()
  def presentedRevision: Long = shown.fold(0L)(_._1)
  def layout: Option[LayoutResult] = shown.map(_._2)
  def invalidatePresentation(): Unit = {
    shown = None
    elements.invalidate()
  }

  /** Rendering is unavailable: preserve drafts, but retire provisional input. */
  def suspendPresentation(): Boolean =
    val composing = composingElement.nonEmpty
    retireElementComposition()
    invalidatePresentation()
    interaction.state = interaction.state.copy(pressed = None)
    runtime.transact {
      runtime.publishFocus(Set.empty, Set.empty, false)
    }
    composing
  def ready: Boolean = shown.exists((_, layout) => layout.root.node == tree)

  /** The presented node of the focused text control, painted focused in cached base drawings. */
  def textFocus: Option[NodeId] = textElement.map(_._2.node.id)
  def needsGeometry(input: HostInput): Boolean =
    if shown.isEmpty then
      true
    else
      input match
        case _: HostInput.Down | _: HostInput.Up | _: HostInput.Move | _: HostInput.Wheel =>
          true
        case _: HostInput.KeyDown | _: HostInput.TextInput | _: HostInput.Composition
            if interaction.focused.exists(_.kind.isInstanceOf[TargetKind.Custom]) =>
          true
        case HostInput.KeyDown(key, _, _) =>
          Set(Key.ArrowLeft, Key.ArrowRight, Key.ArrowUp, Key.ArrowDown, Key.Home, Key.End, Key.Tab).contains(key)
        case _ =>
          false

  /** Reserve actual editor/control keys before local or menu shortcuts. */
  def consumesKey(input: HostInput): Boolean =
    input match
      case HostInput.KeyDown(key, mods, _) =>
        if composingText then
          true
        else if key == Key.Tab && (mods.isEmpty || mods == Mods.Shift) then
          true
        else if textElement.nonEmpty then
          // Printable keys belong to the text channel; declared editing keys and chords belong to the control.
          val request = textElement.get._3
          key match
            case _ if request.chords.contains(Chord(key, mods)) || request.keys.contains(key) =>
              true
            case Key.Character(_) | Key.Space =>
              !mods.has(Mods.Primary) && !mods.has(Mods.Control)
            case _ =>
              false
        else if !mods.isEmpty then
          false
        else
          interaction
            .focused
            .exists(
              _.kind match
                case _: TargetKind.Button =>
                  key == Key.Enter || key == Key.Space
                case _ =>
                  false
            )
      case _ =>
        false
  private def publishFocus()(using UiTxn): Unit =
    val (direct, ancestors, keyboard) = interaction.focusReferences
    runtime.publishFocus(direct, ancestors, keyboard)
  def nativeFocus(active: Boolean): Unit =
    runtime.transact {
      if active then
        publishFocus()
      else
        runtime.publishFocus(Set.empty, Set.empty, false)
    }
    ()
  def invoke(id: Long): Unit = captured
    .bindings
    .get(id)
    .filter(_.enabled)
    .foreach { action =>
      runtime.transact {
        action.run(summon[UiTxn])
        publishFocus()
      }
      ()
    }

  /** Focus has just moved in `interaction`. The element that lost it receives `Lost`, whose refusal vetoes the move;
    * an element that gains it then receives `Gained`.
    */
  private def moved(old: Option[Long]): Boolean =
    val leaving = old.filterNot(focusedElement.contains)
    val allowed = leaving.forall { id =>
      elements.focus(id, FocusEvent.Lost) match
        case None | Some(CommitResult.Committed(_)) =>
          true
        case Some(_) =>
          reject("Focus could not leave this element.")
          false
    }
    if allowed && focusedElement != old then
      focusedElement.foreach { id =>
        elements.focus(id, FocusEvent.Gained) match
          case None | Some(CommitResult.Committed(_)) =>
            ()
          case Some(_) =>
            reject("This element could not accept focus.")
      }
    allowed

  /** Called once before menu/local shortcuts; false preserves their normal fallback. */
  def handleElementKey(input: HostInput): Boolean =
    input match
      case HostInput.KeyDown(key, _, _) if composingElement.nonEmpty =>
        // The platform IME owns keys beneath a composition; Escape abandons it.
        if key == Key.Escape then
          clearComposition()
          elementText(TextInputEvent.CancelComposition)
        true
      case HostInput.KeyDown(key, mods, repeat) if ready && key != Key.Tab =>
        val before = refusals
        staged(services => elements.key(KeyEvent(key, mods, repeat), services)) match
          case Some(CommitResult.Committed(handled)) =>
            cleared(before, handled)
            handled
          case Some(_) =>
            reject("This element action could not be committed.")
            true
          case None =>
            false
      case _ =>
        false
  def handle(input: HostInput, mods: Mods = Mods.none, clicks: Int = 1, routeElementKey: Boolean = true): Unit =
    try
      if input == HostInput.Deactivate then
        // Focus stays, but the focused element is told it lost the window; it cannot veto deactivation.
        focusedElement.foreach { id =>
          elements.focus(id, FocusEvent.Lost) match
            case None | Some(CommitResult.Committed(_)) =>
              ()
            case Some(_) =>
              reject("This element could not handle deactivation.")
        }
      if !routeElementKey || !handleElementKey(input) then
        dispatch(input, mods, clicks)
      if input == HostInput.Deactivate then
        retireElementComposition()
      retireMovedComposition()
    catch
      case _: IllegalArgumentException =>
        reject("This input has an invalid text range.")
      case _: NativeWindow.ClipboardUnavailable =>
        reject("Clipboard is unavailable.")
  def focusReference(reference: Long): Boolean =
    val before = interaction.state
    val previous = focusedElement
    if !interaction.focusRef(reference) then
      false
    else if !moved(previous) then {
      interaction.rollbackPublication(before)
      false
    } else {
      runtime.transact {
        publishFocus()
      }
      true
    }

  /** Keyboard focus on a presented element, as Tab or an assistive focus request would give it. */
  def focusElement(id: Long): Boolean =
    if !elements.shownElement(id) then
      false
    else
      val before = interaction.state
      val previous = focusedElement
      interaction.state = interaction
        .state
        .copy(focused = Some(NodeId(Vector("element", id))), keyboard = true, pressed = None)
      if !moved(previous) then {
        interaction.rollbackPublication(before)
        false
      } else {
        retireMovedComposition()
        runtime.transact {
          publishFocus()
        }
        true
      }
  private def dispatch(input: HostInput, mods: Mods, clicks: Int): Unit =
    val oldElement = focusedElement
    if ready && elements.accepts(input) then
      val before = interaction.state
      input match
        case _: HostInput.Down =>
          interaction.handle(input)
          ()
        case _ =>
          ()
      if !moved(oldElement) then
        interaction.rollbackPublication(before)
      else
        staged(services => elements.pointer(input, mods, clicks, services))
          .foreach {
            case CommitResult.Committed(_) =>
              ()
            case _ =>
              reject("This element action could not be committed.")
          }
        input match
          case _: HostInput.Up | HostInput.Deactivate =>
            interaction.handle(input)
          case _ =>
            ()
        runtime.transact {
          publishFocus()
        }
      return
    input match
      case HostInput.Wheel(x, y, delta) if ready =>
        elements.wheel(x, y, delta, mods) match
          case Some(CommitResult.Committed(true)) =>
            ()
          case Some(CommitResult.Committed(false)) | None =>
            interaction.handle(input)
            ()
          case Some(_) =>
            reject("This element action could not be committed.")
      case HostInput.TextInput(value) if textElement.nonEmpty =>
        elementText(TextInputEvent.Commit(normalize(value)))
      case HostInput.Composition(value, start, length) if textElement.nonEmpty =>
        val (a, b) = compositionRange(value, start, length)
        elementText(
          TextInputEvent.Compose(
            normalize(value),
            TextRange(
              normalize(value.take(value.offsetByCodePoints(0, a))).length,
              normalize(value.take(value.offsetByCodePoints(0, b))).length
            )
          )
        )
      case HostInput.Deactivate =>
        interaction.handle(input)
        ()
      case HostInput.Down(_, _, _) | HostInput.KeyDown(Key.Tab, _, _) =>
        val before = interaction.state
        val decision = interaction.handle(input)
        if !moved(oldElement) then
          interaction.rollbackPublication(before)
        else
          decision.activate.foreach(invoke)
        runtime.transact {
          publishFocus()
        }
        ()
      case _: HostInput.Up =>
        val decision = interaction.handle(input)
        decision.activate.foreach(invoke)
      case _ =>
        val decision = interaction.handle(input)
        decision.activate.foreach(invoke)
        runtime.transact {
          publishFocus()
        }
        ()

  /** Platform line endings never reach an element. */
  private def normalize(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

  /** SDL Cocoa reports codepoint indices; public positions are UTF-16. */
  private def compositionRange(value: String, start: Int, length: Int): (Int, Int) =
    val count = value.codePointCount(0, value.length)
    val a = math.max(0, math.min(count, start))
    val b = math.max(
      a,
      math.min(
        count,
        if length < 0 then
          a
        else
          a + length
      )
    )
    (a, b)

  /** Assistive text requests address an element's exposed text, whether or not it is focused. */
  private def elementAccessibility(action: NativeWindow.AccessibilityAction): Boolean =
    val id = action.target()
    action.kind() match
      case 1 =>
        focusElement(id)
      case 2 =>
        val start = action.start()
        val end = start.toLong + action.length()
        start >= 0 && end >= 0 && end <= Int.MaxValue &&
        deliverTo(id, TextInputEvent.Select(TextRange(start, end.toInt)))
      case 3 =>
        deliverTo(id, TextInputEvent.Replace(normalize(action.value())))
      case _ =>
        false
  private def deliverTo(id: Long, event: TextInputEvent): Boolean =
    val before = refusals
    staged(services => elements.textTo(id, event, services)).exists {
      case CommitResult.Committed(handled) =>
        cleared(before, handled)
        handled
      case _ =>
        reject("This text input could not be committed.")
        false
    }
  def accessibility(action: NativeWindow.AccessibilityAction): Boolean =
    if !ready || action.revision() != presentedRevision then
      false
    else
      val id = action.target()
      if action.kind() == 4 && captured.bindings.get(id).exists(_.enabled) then {
        invoke(id)
        true
      } else if elements.exposesText(id) then
        elementAccessibility(action)
      else
        false
