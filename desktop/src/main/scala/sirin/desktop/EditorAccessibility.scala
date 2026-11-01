package sirin.desktop

import sirin.*
import sirin.host.*

/** Window-owned publication and action addresses from the accepted presentation only. */
final class EditorAccessibility:
  private val tree = new NativeSemanticTree
  private var revision = 0L

  def publish(window: NativeWindow, controller: EditorController, frame: RenderedFrame, active: Boolean = true): Unit =
    val layout = frame.layout
    val nodes = tree.capture(layout, controller.interaction.state.focused.filter(_ => active),
      controller.semanticReferences, controller.interaction.state.part)
    window.beginAccessibility(frame.request.generation)
    nodes.foreach { node =>
      val semantic = node.part.semantics
      val b = node.part.bounds
      val area = Box(b.origin.x.toDouble, b.origin.y.toDouble, b.size.width.toDouble, b.size.height.toDouble)
      val bounds = Array(area.x, area.y, area.width, area.height)
      node.part.text match
        case Some(text) =>
          val shaped = layout.text.get(text.request).getOrElse(
            throw new IllegalStateException("accessibility text was not prepared"))
          val origin = node.origin + text.origin
          val caret = shaped.caret(TextPosition(text.selection.focus, TextAffinity.Downstream))
            .orElse(shaped.caretsAt(text.selection.focus).headOption)
          window.accessibilityField(node.id, node.kind, semantic.name.getOrElse(""), text.request.content,
            semantic.description.getOrElse(""), text.selection.anchor,
            text.selection.focus - text.selection.anchor, node.focused, node.enabled && text.editable, bounds,
            EditorAccessibility.spans(shaped, origin, area, text.selection.focus, caret))
          window.accessibilityLink(node.id, node.parent, node.flags | (if text.editable then 0 else 4))
        case None =>
          window.accessibilityNode(node.id, node.parent, node.kind, semantic.name.getOrElse(""),
            semantic.value.getOrElse(""), semantic.description.getOrElse(""), node.flags,
            semantic.checked.fold(-1)(value => if value then 1 else 0), bounds)
      val actions = NativeSemanticAction.declarations(node.part.actions)
      if actions.nonEmpty then
        window.accessibilityActions(node.id, actions.map(_._1).toArray, actions.map(_._2).toArray)
      val relationships = node.relationshipFacts
      if relationships.exists(_ != 0) then window.accessibilityRelationships(node.id, relationships)
    }
    window.publishAccessibility()
    revision = frame.request.generation
    EditorAccessibility.updateTextInput(window, controller, active)

  def handle(controller: EditorController, action: NativeWindow.AccessibilityAction): Boolean =
    if action.revision() != revision then false
    else tree.nodes.find(_.id == action.target()).exists { node =>
      node.instance.exists { instance =>
        if !node.enabled then false
        else action.kind() match
          case 1 if node.focusable => controller.accessibilityFocus(instance, node.part.part, revision)
          case 4 if node.part.actions(SemanticAction.Activate) =>
            controller.accessibilityActivate(instance, node.part.part, revision)
          case 2 | 3 if node.part.text.exists(_.editable) =>
            controller.accessibility(new NativeWindow.AccessibilityAction(
              revision, instance, action.kind(), action.start(), action.length(), action.value()))
          case kind =>
            NativeSemanticAction.decode(kind, action.value()).exists { semantic =>
              node.part.actions(semantic) &&
                controller.accessibilityAction(instance, node.part.part, semantic, revision)
            }
      }
    }

object EditorAccessibility:
  /** Caret and grapheme spans in window coordinates, clipped to `area`; `focus` uses the supplied caret. */
  private[desktop] def spans(
      text: TextLayout,
      origin: Point,
      area: Box,
      focus: Int,
      focusCaret: Option[TextCaret]
  ): Array[Double] =
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
        val regions = text.selection(a, b)
        if regions.nonEmpty then regions.foreach(r => add(a, b, r.bounds, r.lineIndex))
        else
          // Zero-advance graphemes have no filled selection region, but still own source text.
          val caret = text.caret(TextPosition(a, TextAffinity.Downstream)).orElse(text.caretsAt(a).headOption)
            .getOrElse(throw new IllegalStateException("accessibility grapheme has no caret"))
          add(a, b, caret.bounds, caret.lineIndex)
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
