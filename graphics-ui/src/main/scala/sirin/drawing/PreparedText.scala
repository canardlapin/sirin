package sirin.drawing

import scala.annotation.unused
import sirin as ui
import sirin.graphics as g
import sirin.text.ResolvedTextLayout

/** Source-compatible bridge to the core request value. */
type TextRequest = ui.TextRequest
val TextRequest: ui.TextRequest.type = ui.TextRequest

final case class TextPreparationLimits(
    maxRequests: Int = 1024,
    maxUtf16Units: Long = 1048576,
    maxGlyphs: Int = 262144,
    maxFontBytes: Long = 67108864
):
  require(
    maxRequests > 0 && maxRequests <= 65536 && maxUtf16Units > 0 && maxGlyphs > 0 && maxFontBytes > 0,
    "invalid text preparation budgets"
  )

final class TextNotPrepared(val request: TextRequest)
    extends IllegalArgumentException(
      s"text request was not prepared: ${request.style}, width=${request.maxWidth.toDouble}"
    )

/** Immutable layout inventory, also a lookup-only core TextServices. Nothing here retains the preparer, native owners
  * or a deferred preparation callback.
  */
final class PreparedText private (
    val requests: Vector[TextRequest],
    private val entries: Map[TextRequest, ResolvedTextLayout]
) extends ui.TextServices:
  def get(request: TextRequest): Option[ResolvedTextLayout] = entries.get(request)
  private[drawing] def requireLayout(request: TextRequest): ResolvedTextLayout = entries.getOrElse(
    request,
    throw new TextNotPrepared(request)
  )
  override def layout(content: String, style: ui.TextStyle, maxWidth: ui.Lp)(using
      @unused
      committed: ui.Committed
  ): ResolvedTextLayout = requireLayout(TextRequest(content, style, maxWidth))

object PreparedText:
  val empty: PreparedText = new PreparedText(Vector.empty, Map.empty)

  /** Explicit synchronous preparation before paint, under existing phase and slow-work policy. Duplicate requests call
    * the service once, in first-use order. Failure returns no inventory and never closes caller resources.
    */
  def prepare(
      requests: Vector[TextRequest],
      services: ui.TextServices,
      limits: TextPreparationLimits = TextPreparationLimits()
  )(using ui.Committed): PreparedText =
    validateRequests(requests, limits)
    build(
      requests.distinct,
      request =>
        services.layout(request.content, request.style, request.maxWidth) match
          case layout: ResolvedTextLayout =>
            layout
          case _ =>
            throw new IllegalArgumentException("recording requires a ResolvedTextLayout")
      ,
      limits
    )

  /** Explicit associations for already resolved results, including after native closure. The caller supplies style
    * meaning; source/width must match.
    */
  def fromResolved(
      entries: Vector[(TextRequest, ResolvedTextLayout)],
      limits: TextPreparationLimits = TextPreparationLimits()
  ): PreparedText =
    require(entries.size <= limits.maxRequests, "too many text preparation requests")
    val requests = entries.map(_._1)
    validateRequests(requests, limits)
    val map = entries.toMap
    require(entries.forall((request, layout) => map(request) eq layout), "conflicting prepared text request")
    build(requests.distinct, map.apply, limits)

  private def validateRequests(requests: Vector[TextRequest], limits: TextPreparationLimits): Unit =
    require(requests.size <= limits.maxRequests, "too many text preparation requests")
    require(
      requests.iterator.map(_.content.length.toLong).sum <= limits.maxUtf16Units,
      "text preparation exceeds source budget"
    )

  private def build(
      requests: Vector[TextRequest],
      resolve: TextRequest => ResolvedTextLayout,
      limits: TextPreparationLimits
  ): PreparedText =
    var glyphs = 0L
    var fontBytes = 0L
    val fonts = scala.collection.mutable.HashSet.empty[g.FontData]
    val entries = requests.map { request =>
      val layout = resolve(request)
      require(layout.text == request.content, "prepared text source does not match its request")
      val (faces, count) =
        layout.resolved match
          case p: g.ParagraphLayout =>
            require(
              request.maxWidth.toDouble == Double.PositiveInfinity,
              "unwrapped text requires an unbounded request"
            )
            (p.requestedFonts, p.logicalRuns.iterator.map(_.run.glyphs.size.toLong).sum)
          case p: g.WrappedParagraphLayout =>
            require(request.maxWidth.toDouble == p.maxWidth, "prepared text width does not match its request")
            (p.requestedFonts, p.lines.iterator.flatMap(_.layout.logicalRuns).map(_.run.glyphs.size.toLong).sum)
      glyphs += count
      require(glyphs <= limits.maxGlyphs, "text preparation exceeds glyph budget")
      faces.foreach { face =>
        if fonts.add(face.data) then
          fontBytes += face.data.byteSize
      }
      require(fontBytes <= limits.maxFontBytes, "text preparation exceeds font byte budget")
      request -> layout
    }
    new PreparedText(requests, entries.toMap)
