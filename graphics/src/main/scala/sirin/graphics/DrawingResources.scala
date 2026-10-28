package sirin.graphics

import scala.collection.mutable

/** Limits bound inspection work, not GPU budgets or expanded rendering work. */
final case class InspectionLimits(maxDrawings: Int = 100000, maxCommands: Long = 1000000):
  require(maxDrawings >= 1, "inspection must admit the root drawing")
  require(maxCommands >= 0, "command limit must be nonnegative")

enum InspectionLimit:
  case Drawings(maximum: Int)
  case Commands(maximum: Long)

/** Declared snapshot dependencies in first-encounter order. Counts describe the
  * unique recording graph, not expanded placements, upload volume or GPU work.
  * Zero-opacity and empty-destination operations still declare their resources.
  */
final class DrawingResources private (
    val images: Vector[ImageSnapshot], val fonts: Vector[FontData],
    val uniqueDrawings: Int, val inspectedCommands: Long
):
  val imageBytes: Long = images.foldLeft(0L)((total, image) => total + image.byteSize)
  val fontBytes: Long = fonts.foldLeft(0L)((total, font) => total + font.byteSize)

object DrawingResources:
  /** Iterative traversal visits each shared drawing once and returns either a
    * complete inventory or a limit error, never a silently truncated inventory.
    */
  def inspect(root: Drawing, limits: InspectionLimits = InspectionLimits()): Either[InspectionLimit, DrawingResources] =
    // These final immutable resource classes have identity equality. No content
    // hashing or recursive Drawing.equals is needed to inspect a shared graph.
    val drawings = mutable.HashSet(root)
    val images = mutable.HashSet.empty[ImageSnapshot]
    val orderedImages = mutable.ArrayBuffer.empty[ImageSnapshot]
    val fonts = mutable.HashSet.empty[FontData]
    val orderedFonts = mutable.ArrayBuffer.empty[FontData]
    val stack = mutable.ArrayBuffer(root.commands.iterator)
    var commandCount = 0L
    while stack.nonEmpty do
      val current = stack.last
      if !current.hasNext then stack.remove(stack.size - 1)
      else
        if commandCount >= limits.maxCommands then return Left(InspectionLimit.Commands(limits.maxCommands))
        commandCount += 1
        current.next() match
          case DrawCommand.Draw(child) =>
            if !drawings.contains(child) then
              if drawings.size >= limits.maxDrawings then return Left(InspectionLimit.Drawings(limits.maxDrawings))
              drawings += child
              stack += child.commands.iterator
          case DrawCommand.Image(region, _, _, _) =>
            if images.add(region.image) then orderedImages += region.image
          case DrawCommand.Glyphs(run, _, _) =>
            if fonts.add(run.font.data) then orderedFonts += run.font.data
          case DrawCommand.Fill(_, _, _) | DrawCommand.Stroke(_, _, _) |
              DrawCommand.FillInstances(_, _, _) | DrawCommand.BeginTransform(_) |
              DrawCommand.EndTransform | DrawCommand.BeginClip(_, _) | DrawCommand.EndClip |
              DrawCommand.BeginLayer(_) | DrawCommand.EndLayer => ()
    Right(new DrawingResources(orderedImages.toVector, orderedFonts.toVector, drawings.size, commandCount))
