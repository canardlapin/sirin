package sirin

import scala.annotation.unused

/** The standard text field, composed through public component, element, focus and text-input APIs. Selection,
  * composition and undo history live in a cell owned by the mounted field and publish with the edit's transaction.
  */
private[sirin] object TextFieldElement:
  /** Editing state for the bound cell it was created against; a different cell or external text starts afresh. */
  private final case class Draft(source: AnyRef, editing: TextEditing, notice: Option[String] = None)

  /** What measurement depends on. Selection and caret moves do not re-measure. */
  private final case class Measured(
      display: String,
      label: String,
      hint: String,
      error: Option[String],
      multiline: Boolean
  )

  /** What paint, semantics and text input depend on. */
  private final case class Shown(snapshot: EditSnapshot, measured: Measured)

  private val part = PartId(0)
  private val editingKeys: Set[Key] = Set(
    Key.Backspace,
    Key.Delete,
    Key.ArrowLeft,
    Key.ArrowRight,
    Key.ArrowUp,
    Key.ArrowDown,
    Key.Home,
    Key.End,
    Key.Enter,
    Key.Escape
  )
  private val commandChords: Set[Chord] =
    for
      c <- "acxvz".toSet
      mods <- Set(Mods.Primary, Mods.Primary | Mods.Shift)
    yield Chord(Key.Character(c), mods)

  private def limitNotice(units: Int): String = s"Use at most $units UTF-16 units in this field."

  /** At most 512 UTF-16 units, never ending in half a surrogate pair. */
  private def bounded(message: String): String =
    val clipped = message.take(512)
    if message.length > 512 && clipped.lastOption.exists(Character.isHighSurrogate) then
      clipped.dropRight(1)
    else
      clipped

  private def resolve(field: TextField.Description, text: String, stored: Option[Draft]): Draft = stored
    .filter(d => (d.source eq field.value) && d.editing.snapshot.text == text)
    .getOrElse(Draft(field.value, TextEditing(text)))

  private def present(
      field: TextField.Description,
      text: String,
      problem: Option[String],
      stored: Option[Draft]
  ): Shown =
    val draft = resolve(field, text, stored)
    val error = draft
      .notice
      .orElse(problem)
      .orElse(Option.when(text.length > field.maxUnits)(limitNotice(field.maxUnits)))
    Shown(draft.editing.snapshot, Measured(draft.editing.snapshot.display, field.fieldLabel, field.hint, error, field.multiple))

  private def width(c: Constraints): Lp =
    if c.maxWidth.toDouble.isFinite then
      c.maxWidth
    else
      c.minWidth.max(240.lp)

  /** The text requests for one presented width, in the order paint uses them. */
  private def requests(m: Measured, width: Lp): Vector[TextRequest] =
    val w = width.toDouble
    val limit =
      if m.multiline then
        math.max(1, w - 20)
      else
        Double.PositiveInfinity
    Vector(TextRequest(m.display, TextStyle.Body, Lp(limit))) ++
      Option.when(m.label.nonEmpty)(TextRequest(m.label, TextStyle.Caption, Lp(math.max(1, w)))) ++
      m.error.map(TextRequest(_, TextStyle.Caption, Lp(math.max(1, w)))) ++
      Option.when(m.hint.nonEmpty)(TextRequest(m.hint, TextStyle.Body, Lp(math.max(1, w - 20))))

  /** Field geometry from the layouts of `requests`, shared by every phase and by handlers. */
  private final class Geometry(m: Measured, all: Vector[TextLayout]):
    val layout: TextLayout = all.head
    private val rest = all.tail
    val label: Option[TextLayout] = Option.when(m.label.nonEmpty)(rest.head)
    val error: Option[TextLayout] = m.error.map(_ => rest(label.size))
    val hint: Option[TextLayout] = Option.when(m.hint.nonEmpty)(rest(label.size + error.size))
    val top: Double = label.fold(0.0)(t => math.max(20, t.size.height.toDouble))
    val bottom: Double = error.fold(0.0)(t => math.max(20, t.size.height.toDouble + 2))
    def height: Double =
      top + bottom + (
        if m.multiline then
          140
        else
          layout.size.height.toDouble + 16
      )
    def border(size: Size): Rect = Rect(
      Point(Lp.zero, top.lp),
      Size(size.width, Lp(math.max(0, size.height.toDouble - top - bottom)))
    )
    def area(size: Size): Rect = Rect(
      Point(10.lp, (top + 8).lp),
      Size(Lp(math.max(0, size.width.toDouble - 20)), Lp(math.max(0, size.height.toDouble - top - 16 - bottom)))
    )
    def caret(snapshot: EditSnapshot): TextCaret =
      val head = snapshot.visibleSelection.head
      layout
        .caret(head)
        .getOrElse(
          layout
            .graphemeBoundaries
            .flatMap(layout.caretsAt)
            .minBy(c => math.abs(c.position.utf16Offset - head.utf16Offset))
        )

    /** Scrolled so that the caret stays inside the text area. */
    def origin(snapshot: EditSnapshot, size: Size): Point =
      val a = area(size)
      val c = caret(snapshot).bounds
      Point(
        Lp(
          a.origin.x.toDouble - math.max(0, c.origin.x.toDouble - a.size.width.toDouble + 2)
        ),
        Lp(
          a.origin.y.toDouble -
            math.max(0, c.origin.y.toDouble + c.size.height.toDouble - a.size.height.toDouble)
        )
      )

  private def geometry(m: Measured, width: Lp, text: TextServices)(using Committed): Geometry =
    Geometry(m, requests(m, width).map(r => text.layout(r.content, r.style, r.maxWidth)))

  private val definition =
    new Element[Measured, Shown, Unit, Shown, Unit]:
      override def measureTextRequests(m: Measured, c: Constraints)(using
          @unused
          committed: Committed
      ): Seq[TextRequest] = requests(m, width(c))

      override def measure(m: Measured, c: Constraints, text: TextServices)(using Committed): Size =
        val w = width(c)
        val g = geometry(m, w, text)
        Size(w.max(c.minWidth).min(c.maxWidth), Lp(g.height).max(c.minHeight).min(c.maxHeight))

      override def textRequests(
          shown: Shown,
          @unused
          presentation: Unit,
          size: Size
      )(using
          @unused
          c: Committed
      ): Seq[TextRequest] = requests(shown.measured, size.width)

      def paint(shown: Shown, presentation: Unit, size: Size, canvas: Canvas)(using Committed): Unit =
        paint(shown, presentation, size, canvas, ElementFeedback())

      override def paint(shown: Shown, presentation: Unit, size: Size, canvas: Canvas, feedback: ElementFeedback)(
          using Committed
      ): Unit =
        val g = geometry(shown.measured, size.width, canvas.textServices)
        val theme = canvas.theme
        val border = g.border(size)
        canvas.fill(
          border,
          if shown.measured.error.nonEmpty then
            Color.rgba(222, 100, 100)
          else
            Color.rgba(89, 103, 126)
        )
        canvas.fill(
          Rect(
            Point(1.lp, border.origin.y + 1.lp),
            Size((border.size.width - 2.lp).max(Lp.zero), (border.size.height - 2.lp).max(Lp.zero))
          ),
          Color.rgba(28, 34, 46)
        )
        g.label.foreach(t => canvas.text(t, Point.origin, theme.color(ColorRole.OnSurface)))
        g.error
          .foreach(t =>
            canvas.text(t, Point(Lp.zero, border.origin.y + border.size.height + 2.lp), Color.rgba(255, 145, 145))
          )
        canvas.clipped(g.area(size)) { inner =>
          val layout = g.layout
          val origin = g.origin(shown.snapshot, size)
          def selected(from: Int, until: Int): Vector[TextSelectionRegion] =
            val stops = layout.graphemeBoundaries
            val a = stops.filter(_ <= from).lastOption.getOrElse(0)
            val b = stops.find(_ >= until).getOrElse(layout.text.length)
            layout.selection(a, b)
          val selection = shown.snapshot.visibleSelection
          if feedback.focused && !selection.empty then
            selected(selection.start, selection.end)
              .foreach(r => inner.fill(r.bounds.translate(origin), Color.rgba(62, 110, 169)))
          if layout.text.isEmpty then
            g.hint.foreach(t => inner.text(t, origin, theme.color(ColorRole.Muted)))
          else
            inner.text(layout, origin, theme.color(ColorRole.OnSurface))
          shown
            .snapshot
            .marked
            .foreach { (a, b) =>
              selected(a, b).foreach { r =>
                val box = r.bounds.translate(origin)
                inner.fill(
                  Rect(
                    Point(box.origin.x, box.origin.y + box.size.height - 2.lp),
                    Size(box.size.width, 1.lp)
                  ),
                  theme.color(ColorRole.Accent)
                )
              }
            }
          if feedback.focused then
            val caret = g.caret(shown.snapshot).bounds.translate(origin)
            inner.fill(Rect(caret.origin, Size(1.lp, caret.size.height)), theme.color(ColorRole.Accent))
        }

      override def textInput(shown: Shown, size: Size, text: TextServices)(using Committed): Option[TextInputRequest] =
        val g = geometry(shown.measured, size.width, text)
        val caret = g.caret(shown.snapshot).bounds.translate(g.origin(shown.snapshot, size))
        Some(TextInputRequest(caret, editingKeys, commandChords, shown.snapshot.preedit.nonEmpty))

      override def semantics(shown: Shown, size: Size, text: TextServices)(using Committed): Seq[SemanticNode] =
        val g = geometry(shown.measured, size.width, text)
        val m = shown.measured
        val selection = shown.snapshot.visibleSelection
        Vector(
          SemanticNode(
            part,
            Semantics(
              Role.TextInput,
              name = Option.when(m.label.nonEmpty)(m.label),
              description = m.error.orElse(Option.when(m.hint.nonEmpty)(m.hint)),
              value = Some(m.display),
              invalid = m.error.nonEmpty
            ),
            g.area(size),
            Set(SemanticAction.Focus),
            Some(
              TextSemantics(
                requests(m, size.width).head,
                g.origin(shown.snapshot, size),
                TextRange(selection.anchor.utf16Offset, selection.head.utf16Offset),
                multiline = m.multiline
              )
            )
          )
        )

  /** Field policy over the owned draft. Every edit publishes the bound value and the draft in one transaction. */
  private final class Handlers(props: Signal[TextField.Description], draft: Cell[Option[Draft]])
      extends ElementHandlers:
    private def current(using UiTxn): (TextField.Description, Draft) =
      val field = props.get
      (field, resolve(field, field.value.get, draft.get))

    private def clean(field: TextField.Description, value: String): String =
      val normal = TextEditing.normalize(value)
      if field.multiple then
        normal.replace("\t", "    ")
      else
        normal.replace('\n', ' ').replace('\t', ' ')

    /** Keeps the draft, ends any composition and reports the bounded reason. Always false. */
    private def refuse(d: Draft, reason: String, services: TextEditingServices)(using UiTxn): Boolean =
      val safe = bounded(reason)
      val editing =
        if d.editing.snapshot.preedit.nonEmpty then
          services.cancelComposition()
          d.editing.cancel
        else
          d.editing
      draft.set(Some(d.copy(editing = editing, notice = Some(safe))))
      services.refused(safe)
      false

    /** Publishes `next`, then runs `onCommit` when asked. A refusal inside either keeps the bound value and draft. */
    private def publish(
        field: TextField.Description,
        d: Draft,
        next: => TextEditing,
        services: TextEditingServices,
        commit: Boolean = false
    )(using txn: UiTxn): Boolean =
      val candidate =
        try Right(next)
        catch
          case _: IllegalArgumentException =>
            Left("This edit exceeds the field limit or has an invalid text range.")
      candidate match
        case Left(reason) =>
          refuse(d, reason, services)
        case Right(editing) =>
          val changed = editing.snapshot.text != d.editing.snapshot.text
          if changed && editing.snapshot.text.length > field.maxUnits then
            refuse(d, limitNotice(field.maxUnits), services)
          else
            UiTxn.attempt {
              if changed then
                field.value.set(editing.snapshot.text)
              if commit then
                field.commit(summon[UiTxn])
            } match
              case Attempt.Accepted(_) =>
                val notice =
                  if changed || commit then
                    None
                  else
                    d.notice
                draft.set(Some(Draft(field.value, editing, notice)))
                true
              case Attempt.Refused(reason) =>
                refuse(d, reason, services)

    private def wordStops(text: String, backward: Boolean, services: TextEditingServices): Vector[Int] =
      val stops = services.graphemeBoundaries(text)
      (
        Vector(0, text.length) ++
          services
            .words(text)
            .map(w =>
              if backward then
                w.start
              else
                w.end
            )
      ).distinct.sorted.filter(stops.contains)

    /** The geometry the routed frame presented, looked up without shaping. */
    private def presented(field: TextField.Description, d: Draft, services: TextEditingServices)(using
        UiTxn
    ): Option[(Geometry, Size)] = services
      .presentedSize
      .flatMap { size =>
        val m = present(field, d.editing.snapshot.text, field.problem.get, Some(d)).measured
        val found = requests(m, size.width).map(services.presented)
        Option.when(found.forall(_.nonEmpty))((Geometry(m, found.flatten), size))
      }

    /** The text position under a pointer, in the presented display text. */
    private def point(event: PointerEvent, field: TextField.Description, d: Draft, services: TextEditingServices)(
        using UiTxn
    ): Option[TextPosition] = presented(field, d, services).map { (g, size) =>
      val origin = g.origin(d.editing.snapshot, size)
      g.layout.hit(Point(event.position.x - origin.x, event.position.y - origin.y)).primary.position
    }

    override def text(event: TextInputEvent, services: TextEditingServices)(using UiTxn): Boolean =
      val (field, d) = current
      val editing = d.editing
      event match
        case TextInputEvent.Commit(value) =>
          publish(
            field,
            d,
            if editing.snapshot.preedit.nonEmpty then
              editing.commit(clean(field, value), services.graphemeBoundaries)
            else
              editing.replace(clean(field, value), services.graphemeBoundaries, coalesce = true),
            services
          )
        case TextInputEvent.Compose(value, range) =>
          publish(
            field,
            d,
            editing.mark(
              clean(field, value),
              clean(field, value.take(range.start)).length,
              clean(field, value.take(range.end)).length
            ),
            services
          )
        case TextInputEvent.CancelComposition =>
          publish(field, d, editing.cancel, services)
        case TextInputEvent.Select(range) =>
          val stops = services.graphemeBoundaries(editing.snapshot.text)
          if range.end > editing.snapshot.text.length || !stops.contains(range.anchor) || !stops.contains(range.focus)
          then
            false
          else
            publish(
              field,
              d,
              editing.select(
                EditSelection(
                  TextPosition(range.anchor, TextAffinity.Downstream),
                  TextPosition(range.focus, TextAffinity.Upstream)
                ),
                services.graphemeBoundaries
              ),
              services
            )
        case TextInputEvent.Replace(value) =>
          publish(field, d, editing.selectAll.replace(clean(field, value), services.graphemeBoundaries), services)

    override def key(event: KeyEvent, services: TextEditingServices)(using UiTxn): Boolean =
      val (field, d) = current
      val editing = d.editing
      val primary = event.mods.has(Mods.Primary)
      val shift = event.mods.has(Mods.Shift)
      val word = event.mods.has(Mods.Alt)
      val boundaries = services.graphemeBoundaries
      def apply(next: => TextEditing): Boolean =
        publish(field, d, next, services)
        true
      event.key match
        case Key.Character(c) if primary =>
          c.toLower match
            case 'a' =>
              apply(editing.selectAll)
            case 'c' =>
              if !editing.snapshot.selection.empty then
                services.copy(editing.selected)
              apply(editing.cancel)
            case 'x' =>
              if !editing.snapshot.selection.empty then
                services.copy(editing.selected)
                apply(editing.replace("", boundaries))
              else
                true
            case 'v' =>
              services.clipboard match
                case Some(value) =>
                  apply(editing.replace(clean(field, value), boundaries))
                case None =>
                  refuse(d, "Clipboard is unavailable.", services)
                  true
            case 'z' =>
              apply(
                if shift then
                  editing.redo
                else
                  editing.undo
              )
            case _ =>
              false
        case Key.Backspace | Key.Delete =>
          val backward = event.key == Key.Backspace
          apply(
            editing.delete(backward, word, boundaries, wordStops(editing.snapshot.text, backward, services))
          )
        case Key.ArrowLeft | Key.ArrowRight | Key.ArrowUp | Key.ArrowDown | Key.Home | Key.End =>
          presented(field, d, services).foreach { (g, _) =>
            val actual =
              if primary && event.key == Key.ArrowLeft then
                Key.Home
              else if primary && event.key == Key.ArrowRight then
                Key.End
              else
                event.key
            apply(
              editing.navigate(
                g.layout,
                actual,
                shift,
                word,
                primary && (event.key == Key.Home || event.key == Key.End),
                boundaries,
                wordStops(editing.snapshot.text, event.key == Key.ArrowLeft, services)
              )
            )
          }
          true
        case Key.Enter =>
          if !field.multiple || primary then
            publish(field, d, editing.cancel, services, commit = true)
            true
          else
            apply(editing.replace("\n", boundaries))
        case Key.Escape =>
          true
        case _ =>
          false

    override def pointer(event: PointerEvent, services: TextEditingServices)(using UiTxn): PointerResponse =
      val (field, d) = current
      val editing = d.editing
      val boundaries = services.graphemeBoundaries
      event.phase match
        case PointerPhase.Down if event.button == PointerButton.Primary && event.hit != Hit.Miss =>
          if editing.snapshot.preedit.nonEmpty then
            services.cancelComposition()
          point(event, field, d, services).foreach { p =>
            val old = editing.cancel
            val selection =
              if event.clicks >= 3 then
                EditSelection(
                  TextPosition(0, TextAffinity.Downstream),
                  TextPosition(old.snapshot.text.length, TextAffinity.Upstream)
                )
              else if event.clicks == 2 then
                services
                  .words(old.snapshot.text)
                  .find(w => p.utf16Offset >= w.start && p.utf16Offset < w.end)
                  .fold(EditSelection.at(p.utf16Offset))(w =>
                    EditSelection(
                      TextPosition(w.start, TextAffinity.Downstream),
                      TextPosition(w.end, TextAffinity.Upstream)
                    )
                  )
              else
                EditSelection(
                  if event.mods.has(Mods.Shift) then
                    old.snapshot.selection.anchor
                  else
                    p
                  ,
                  p
                )
            publish(field, d, old.select(selection, boundaries), services)
          }
          PointerResponse.Capture
        case PointerPhase.Move if event.captured =>
          point(event, field, d, services).foreach { p =>
            publish(field, d, editing.select(EditSelection(editing.snapshot.selection.anchor, p), boundaries), services)
          }
          PointerResponse.Handled
        case PointerPhase.Up | PointerPhase.Cancel if event.captured =>
          PointerResponse.Release
        case _ =>
          PointerResponse.Ignore

    override def focus(event: FocusEvent)(using txn: UiTxn): Unit =
      if event == FocusEvent.Lost then
        val (field, d) = current
        if d.editing != d.editing.cancel then
          draft.set(Some(d.copy(editing = d.editing.cancel)))
        field.blur(txn)

  /** Depends on the bound cell, so binding a different cell mounts a new field even when the text is equal. */
  private val component = Component.withDeps[AnyRef, TextField.Description]("text-field") { (_, scope, props) =>
    val draft = scope.cell(Option.empty[Draft])
    val shown = (props, props.flatMap(_.value), props.flatMap(_.problem), draft).mapN(present)
    Element(
      "text-field",
      ElementInputs(shown.map(_.measured), shown, Signal.const(()), shown, Presentation.const(())),
      definition,
      focusable = true,
      handlers = Some(Handlers(props, draft))
    )
  }

  def view(field: TextField.Description): View = component.bind(field.value)(field)
