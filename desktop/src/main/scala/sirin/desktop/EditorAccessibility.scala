package sirin.desktop

import sirin.*
import sirin.host.*

/** Main-thread native snapshot, copied only from the accepted presented layout. */
object EditorAccessibility:
  def publish(window: NativeWindow, controller: EditorController, frame: RenderedFrame, active: Boolean = true): Unit =
    val layout = frame.layout
    window.beginAccessibility(frame.request.generation)
    def buttons(p: Placed): Unit =
      p.node.content match
        case Content.Custom(element) if p.clip.width > 0 && p.clip.height > 0 =>
          val size = Size(p.bounds.width.lp, p.bounds.height.lp)
          element
            .semantics(size, layout.text)
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
                  // Spans outside the text node, such as text scrolled out of a field, are clipped away.
                  val b = node.bounds
                  val area = Box(
                    p.bounds.x + b.origin.x.toDouble,
                    p.bounds.y + b.origin.y.toDouble,
                    b.size.width.toDouble,
                    b.size.height.toDouble
                  ).intersect(p.clip)
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
                    spans(shaped, origin, area, text.selection.focus, caret)
                  )
                }
            }
          element
            .activation
            .foreach { activation =>
              element
                .semantics(Size(p.bounds.width.lp, p.bounds.height.lp), layout.text)
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
    updateTextInput(window, controller, active)

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
  def updateTextInput(window: NativeWindow, controller: EditorController, active: Boolean): Unit =
    val element = controller.textElement.filter(_ => active)
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
    if element.isEmpty then
      window.textInput(0, 0, 0, 1, 1)
