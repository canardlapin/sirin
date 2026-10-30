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
            val spans = Vector.newBuilder[Double]
            def add(from: Int, until: Int, rect: Rect, line: Int): Unit =
              val raw = Box(
                origin.x.toDouble + rect.origin.x.toDouble,
                origin.y.toDouble + rect.origin.y.toDouble,
                math.max(1, rect.size.width.toDouble),
                rect.size.height.toDouble
              )
              val box = raw.intersect(area)
              spans ++= Vector(from.toDouble, until.toDouble, box.x, box.y, box.width, box.height, line.toDouble)
            val stops = text.graphemeBoundaries
            stops.foreach { o =>
              val caret =
                if o == snapshot.visibleSelection.head.utf16Offset then
                  Some(layout.editorCaret(p))
                else
                  text.caret(TextPosition(o, TextAffinity.Downstream)).orElse(text.caretsAt(o).headOption)
              caret.foreach(c => add(o, o, c.bounds, c.lineIndex))
            }
            stops
              .zip(stops.drop(1))
              .foreach { (a, b) =>
                text.selection(a, b).foreach(r => add(a, b, r.bounds, r.lineIndex))
              }
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
              spans.result().toArray
            )
          case _ =>
            ()
      }
    def buttons(p: Placed): Unit =
      p.node.content match
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
    if !active || controller.focusedId.isEmpty then
      window.textInput(0, 0, 0, 1, 1)
