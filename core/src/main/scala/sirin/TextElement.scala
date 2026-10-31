package sirin

import scala.annotation.unused

/** `Text` as a public element: prepared text measured at the offered width and painted at the measured width. */
private[sirin] object TextElement:
  private final case class Shape(content: String, style: TextStyle)
  private final case class Look(content: String, style: TextStyle, color: ColorRole)
  private val part = PartId(0)

  private val definition =
    new Element[Shape, Look, Unit, String, Unit]:
      override def measureTextRequests(shape: Shape, c: Constraints)(using
          @unused
          committed: Committed
      ): Seq[TextRequest] = Vector(TextRequest(shape.content, shape.style, c.maxWidth))

      override def measure(shape: Shape, c: Constraints, services: TextServices)(using Committed): Size =
        val text = services.layout(shape.content, shape.style, c.maxWidth)
        Size(text.size.width.max(c.minWidth).min(c.maxWidth), text.size.height.max(c.minHeight).min(c.maxHeight))

      override def textRequests(
          look: Look,
          @unused
          presentation: Unit,
          size: Size
      )(using
          @unused
          c: Committed
      ): Seq[TextRequest] = Vector(TextRequest(look.content, look.style, size.width))

      def paint(look: Look, presentation: Unit, size: Size, canvas: Canvas)(using Committed): Unit =
        val text = canvas.textServices.layout(look.content, look.style, size.width)
        canvas.text(text, Point.origin, canvas.theme.color(look.color))

      override def semantics(content: String, size: Size)(using
          @unused
          c: Committed
      ): Seq[SemanticNode] = Vector(
        SemanticNode(part, Semantics(Role.Label, name = Some(content)), Rect(Point.origin, size), Set.empty)
      )

  def view(text: Text.Description): View =
    val shape = text.content.map(Shape(_, text.textStyle))
    val look = text.content.map(Look(_, text.textStyle, text.colorRole))
    Element(
      "text",
      ElementInputs(shape, look, Signal.const(()), text.content, Presentation.const(())),
      definition
    )
