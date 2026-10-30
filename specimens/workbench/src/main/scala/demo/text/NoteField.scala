package demo.text

import scala.annotation.unused
import sirin.*

/** A one-line editable element written only against the public seam: the text channel, IME composition, staged
  * editing services and text-range semantics. Deliberately outside the sirin package.
  */
object NoteField:
  /** `composition` is provisional IME text that replaces `selection` until it commits. */
  final case class State(text: String, selection: TextRange, composition: Option[(String, TextRange)] = None):
    require(selection.end <= text.length, "selection must lie inside the text")
    def display: String = composition.fold(text)((c, _) => text.take(selection.start) + c + text.drop(selection.end))

    /** The selection in displayed text; inside a composition it is the IME's own selection. */
    def shown: TextRange = composition.fold(selection) { (_, r) =>
      TextRange(selection.start + r.anchor, selection.start + r.focus)
    }

  object State:
    def apply(text: String): State = State(text, TextRange.at(text.length))

  private val part = PartId(0)
  private val keys: Set[Key] = Set(Key.ArrowLeft, Key.ArrowRight, Key.Home, Key.End, Key.Backspace, Key.Delete)
  private val chords: Set[Chord] = "acxv".map(c => Chord(Key.Character(c), Mods.Primary)).toSet
  private val inset = 6.lp
  private def request(state: State): TextRequest = TextRequest(state.display, TextStyle.Body)

  private val definition =
    new Element[State, State, Unit, State, Unit]:
      override def measureTextRequests(state: State, c: Constraints)(using
          @unused
          committed: Committed
      ): Seq[TextRequest] = Vector(request(state))

      override def measure(state: State, c: Constraints, text: TextServices)(using Committed): Size =
        val line = text.layout(state.display, TextStyle.Body, Lp(Double.PositiveInfinity))
        val width =
          if c.maxWidth.toDouble.isFinite then
            c.maxWidth
          else
            line.size.width + inset * 2
        Size(width.max(c.minWidth), (line.size.height + inset * 2).max(c.minHeight).min(c.maxHeight))

      override def textRequests(
          state: State,
          @unused
          presentation: Unit,
          @unused
          size: Size
      )(using
          @unused
          c: Committed
      ): Seq[TextRequest] = Vector(request(state))

      def paint(state: State, presentation: Unit, size: Size, canvas: Canvas)(using Committed): Unit =
        paint(state, presentation, size, canvas, ElementFeedback())

      override def paint(state: State, presentation: Unit, size: Size, canvas: Canvas, feedback: ElementFeedback)(
          using Committed
      ): Unit =
        val bounds = Rect(Point.origin, size)
        val line = canvas.textServices.layout(state.display, TextStyle.Body, Lp(Double.PositiveInfinity))
        val at = Point(inset, inset)
        canvas.fill(bounds, canvas.theme.color(ColorRole.SurfaceRaised))
        canvas.clipped(bounds) { inner =>
          val shown = state.shown
          if feedback.focused && !shown.isEmpty then
            line
              .selection(shown.start, shown.end)
              .foreach(r => inner.fill(r.bounds.translate(at), Color.rgba(50, 130, 220, 90)))
          inner.text(line, at, inner.theme.color(ColorRole.OnSurface))
          state
            .composition
            .foreach { (c, _) =>
              line
                .selection(state.selection.start, state.selection.start + c.length)
                .foreach { r =>
                  val b = r.bounds.translate(at)
                  val y = b.origin.y + b.size.height
                  inner.line(Point(b.origin.x, y), Point(b.origin.x + b.size.width, y), Color.rgba(220, 220, 220), 1.lp)
                }
            }
          if feedback.focused then
            caret(state, line)
              .foreach { c =>
                val b = c.bounds.translate(at)
                inner.line(b.origin, Point(b.origin.x, b.origin.y + b.size.height), Color.rgba(240, 240, 240), 1.lp)
              }
        }
        if feedback.focused && feedback.keyboard then
          canvas.stroke(bounds, canvas.theme.color(ColorRole.FocusRing), 2.lp)

      override def textInput(state: State, size: Size, text: TextServices)(using Committed): Option[TextInputRequest] =
        val line = text.layout(state.display, TextStyle.Body, Lp(Double.PositiveInfinity))
        caret(state, line)
          .map(c =>
            TextInputRequest(c.bounds.translate(Point(inset, inset)), keys, chords, state.composition.nonEmpty)
          )

      override def semantics(state: State, size: Size)(using
          @unused
          c: Committed
      ): Seq[SemanticNode] = Vector(
        SemanticNode(
          part,
          Semantics(Role.TextInput, name = Some("Note"), value = Some(state.display)),
          Rect(Point.origin, size),
          Set(SemanticAction.Focus),
          Some(TextSemantics(request(state), Point(inset, inset), state.shown))
        )
      )

  private def caret(state: State, line: TextLayout): Option[TextCaret] =
    val at = state.shown.focus
    line.caret(TextPosition(at, TextAffinity.Downstream)).orElse(line.caretsAt(at).headOption)

  /** Pure edits over a state; segmentation comes from the platform services. */
  private def insert(state: State, value: String, services: TextEditingServices): State =
    val clean = value.replace('\n', ' ').replace('\t', ' ')
    val next = state.text.take(state.selection.start) + clean + state.text.drop(state.selection.end)
    val raw = state.selection.start + clean.length
    val at = services.graphemeBoundaries(next).find(_ >= raw).getOrElse(next.length)
    State(next, TextRange.at(at))

  private def step(state: State, forward: Boolean, services: TextEditingServices): Int =
    val stops = services.graphemeBoundaries(state.text)
    val from = state.selection.focus
    if forward then
      stops.find(_ > from).getOrElse(state.text.length)
    else
      stops.filter(_ < from).lastOption.getOrElse(0)

  private final class Handlers(value: Cell[State]) extends ElementHandlers:
    override def text(event: TextInputEvent, services: TextEditingServices)(using UiTxn): Boolean =
      val state = value.get
      event match
        case TextInputEvent.Commit(text) =>
          value.set(insert(state.copy(composition = None), text, services))
          true
        case TextInputEvent.Compose(text, range) =>
          value.set(state.copy(composition = Option.when(text.nonEmpty)((text, range))))
          true
        case TextInputEvent.CancelComposition =>
          value.set(state.copy(composition = None))
          true
        case TextInputEvent.Select(range) =>
          val stops = services.graphemeBoundaries(state.text)
          if !stops.contains(range.anchor) || !stops.contains(range.focus) then
            false
          else
            value.set(State(state.text, range))
            true
        case TextInputEvent.Replace(text) =>
          value.set(insert(State(state.text, TextRange(0, state.text.length)), text, services))
          true

    override def key(event: KeyEvent, services: TextEditingServices)(using UiTxn): Boolean =
      val state = value.get
      val extend = event.mods.has(Mods.Shift)
      def move(to: Int): Boolean =
        value.set(
          State(
            state.text,
            if extend then
              TextRange(state.selection.anchor, to)
            else
              TextRange.at(to)
          )
        )
        true
      event.key match
        case Key.Character(c) if event.mods.has(Mods.Primary) =>
          c.toLower match
            case 'a' =>
              value.set(State(state.text, TextRange(0, state.text.length)))
              true
            case 'c' =>
              services.copy(state.text.substring(state.selection.start, state.selection.end))
              true
            case 'x' =>
              services.copy(state.text.substring(state.selection.start, state.selection.end))
              value.set(insert(state, "", services))
              true
            case 'v' =>
              services.clipboard.foreach(text => value.set(insert(state, text, services)))
              true
            case _ =>
              false
        case Key.ArrowLeft =>
          move(step(state, forward = false, services))
        case Key.ArrowRight =>
          move(step(state, forward = true, services))
        case Key.Home =>
          move(0)
        case Key.End =>
          move(state.text.length)
        case Key.Backspace | Key.Delete if state.selection.isEmpty =>
          val to = step(state, event.key == Key.Delete, services)
          value.set(insert(State(state.text, TextRange(state.selection.focus, to)), "", services))
          true
        case Key.Backspace | Key.Delete =>
          value.set(insert(state, "", services))
          true
        case _ =>
          false

    override def pointer(event: PointerEvent, services: TextEditingServices)(using UiTxn): PointerResponse =
      val state = value.get
      def offset: Option[Int] = services
        .presented(request(state))
        .map(_.hit(Point(event.position.x - inset, event.position.y - inset)).primary.position.utf16Offset)
      event.phase match
        case PointerPhase.Down if event.button == PointerButton.Primary && event.hit != Hit.Miss =>
          offset.foreach { at =>
            val word = services.words(state.text).find(w => at >= w.start && at < w.end)
            val selection =
              if event.clicks >= 2 then
                word.getOrElse(TextRange.at(at))
              else if event.mods.has(Mods.Shift) then
                TextRange(state.selection.anchor, at)
              else
                TextRange.at(at)
            value.set(State(state.text, selection))
          }
          PointerResponse.Capture
        case PointerPhase.Move if event.captured =>
          offset.foreach(at => value.set(State(state.text, TextRange(state.selection.anchor, at))))
          PointerResponse.Handled
        case PointerPhase.Up | PointerPhase.Cancel if event.captured =>
          PointerResponse.Release
        case _ =>
          PointerResponse.Ignore

  def apply(value: Cell[State]): View = Element(
    "note-field",
    ElementInputs(value, value, Signal.const(()), value, Presentation.const(())),
    definition,
    focusable = true,
    handlers = Some(Handlers(value))
  )
