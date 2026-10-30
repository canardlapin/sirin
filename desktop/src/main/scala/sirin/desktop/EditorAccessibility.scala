package sirin.desktop

import sirin.*
import sirin.host.*

/** Main-thread native snapshot, copied only from the accepted presented layout. */
object EditorAccessibility:
  def publish(window: NativeWindow, controller: EditorController, frame: RenderedFrame, active: Boolean = true): Unit =
    val layout = frame.layout
    window.beginAccessibility(frame.request.generation)
    layout
      .fields
      .filter(p => p.clip.width > 0 && p.clip.height > 0)
      .foreach { p =>
        p.node.content match
          case Content.Editable(id, _, label, hint, multiple, error) =>
            val snapshot = layout.editorState(p)
            val text = p.text.get
            val origin = layout.textOrigin(p)
            val area = layout.editorArea(p).intersect(p.clip)
            val selection = snapshot.visibleSelection
            window.accessibilityField(
              id,
              if multiple then
                1
              else
                0
              ,
              label,
              snapshot.display,
              error.getOrElse(hint),
              selection.anchor.utf16Offset,
              selection.head.utf16Offset - selection.anchor.utf16Offset,
              active && controller.focusedId.contains(id),
              true,
              Array(p.clip.x, p.clip.y, p.clip.width, p.clip.height),
              spans(text, origin, area, snapshot.visibleSelection.head.utf16Offset, Some(layout.editorCaret(p)))
            )
          case _ =>
            ()
      }
    def buttons(p: Placed): Unit =
      p.node.content match
        case Content.Custom(element) if p.clip.width > 0 && p.clip.height > 0 =>
          val size = Size(p.bounds.width.lp, p.bounds.height.lp)
          element
            .semantics(size)
            .iterator
            .flatMap(node => node.text.map(node -> _))
            .nextOption()
            .foreach { (node, text) =>
              layout
                .text
                .get(text.request)
                .foreach { shaped =>
                  val origin = Point(
                    (p.bounds.x + text.origin.x.toDouble).lp,
                    (p.bounds.y + text.origin.y.toDouble).lp
                  )
                  val caret = shaped
                    .caret(TextPosition(text.selection.focus, TextAffinity.Downstream))
                    .orElse(shaped.caretsAt(text.selection.focus).headOption)
                  window.accessibilityField(
                    element.instance,
                    if text.multiline then
                      1
                    else
                      0
                    ,
                    node.semantics.name.getOrElse(""),
                    text.request.content,
                    node.semantics.description.getOrElse(""),
                    text.selection.anchor,
                    text.selection.focus - text.selection.anchor,
                    active && controller.interaction.state.focused.contains(p.node.id),
                    text.editable,
                    Array(p.clip.x, p.clip.y, p.clip.width, p.clip.height),
                    spans(shaped, origin, p.clip, text.selection.focus, caret)
                  )
                }
            }
          element
            .activation
            .foreach { activation =>
              element
                .semantics(Size(p.bounds.width.lp, p.bounds.height.lp))
                .find(node => node.part == activation.part && node.semantics.role == Role.Button)
                .foreach { node =>
                  window.accessibilityField(
                    element.instance,
                    2,
                    node.semantics.name.getOrElse(""),
                    "",
                    "",
                    0,
                    0,
                    active && controller.interaction.state.focused.contains(p.node.id),
                    activation.enabled,
                    Array(p.clip.x, p.clip.y, p.clip.width, p.clip.height),
                    Array.emptyDoubleArray
                  )
                }
            }
        case Content.Button(id, label, enabled, _, _) =>
          window.accessibilityField(
            id,
            2,
            label,
            "",
            "",
            0,
            0,
            active && controller.interaction.state.focused.contains(p.node.id),
            enabled,
            Array(p.clip.x, p.clip.y, p.clip.width, p.clip.height),
            Array.emptyDoubleArray
          )
        case _ =>
          ()
      p.children.foreach(buttons)
    buttons(layout.root)
    window.publishAccessibility()
    updateTextInput(window, controller, frame, active)

  /** Caret and grapheme spans in window coordinates, clipped to `area`; `focus` uses the supplied caret. */
  private def spans(text: TextLayout, origin: Point, area: Box, focus: Int, focusCaret: Option[TextCaret]): Array[Double] =
    val result = Vector.newBuilder[Double]
    def add(from: Int, until: Int, rect: Rect, line: Int): Unit =
      val raw = Box(
        origin.x.toDouble + rect.origin.x.toDouble,
        origin.y.toDouble + rect.origin.y.toDouble,
        math.max(1, rect.size.width.toDouble),
        rect.size.height.toDouble
      )
      val box = raw.intersect(area)
      result ++= Vector(from.toDouble, until.toDouble, box.x, box.y, box.width, box.height, line.toDouble)
    val stops = text.graphemeBoundaries
    stops.foreach { o =>
      val caret =
        if o == focus then
          focusCaret
        else
          text.caret(TextPosition(o, TextAffinity.Downstream)).orElse(text.caretsAt(o).headOption)
      caret.foreach(c => add(o, o, c.bounds, c.lineIndex))
    }
    stops
      .zip(stops.drop(1))
      .foreach { (a, b) =>
        text.selection(a, b).foreach(r => add(a, b, r.bounds, r.lineIndex))
      }
    result.result().toArray

  /** Enable input immediately on focus using accepted geometry; the next frame refines the caret. */
  def updateTextInput(window: NativeWindow, controller: EditorController, frame: RenderedFrame, active: Boolean): Unit =
    val layout = frame.layout
    controller
      .focusedId
      .filter(_ => active)
      .flatMap(id => layout.fields.find(_.node.id == NodeId(Vector("editor", id))))
      .foreach { p =>
        val origin = layout.textOrigin(p)
        val caret = controller
          .snapshots
          .get(controller.focusedId.get)
          .flatMap(s => p.text.flatMap(_.caret(s.visibleSelection.head)))
          .getOrElse(layout.editorCaret(p))
        val c = caret.bounds
        val area = layout.editorArea(p).intersect(p.clip)
        val rect = Box(
          origin.x.toDouble + c.origin.x.toDouble,
          origin.y.toDouble + c.origin.y.toDouble,
          1,
          c.size.height.toDouble
        ).intersect(area)
        window.textInput(1, rect.x, rect.y, math.max(1, rect.width), math.max(1, rect.height))
      }
    val element = controller.textElement.filter(_ => active && controller.focusedId.isEmpty)
    element.foreach { (_, p, request) =>
      val c = request.caret
      val rect = Box(
        p.bounds.x + c.origin.x.toDouble,
        p.bounds.y + c.origin.y.toDouble,
        math.max(1, c.size.width.toDouble),
        c.size.height.toDouble
      ).intersect(p.clip)
      window.textInput(1, rect.x, rect.y, math.max(1, rect.width), math.max(1, rect.height))
    }
    if !active || (controller.focusedId.isEmpty && element.isEmpty) then
      window.textInput(0, 0, 0, 1, 1)
