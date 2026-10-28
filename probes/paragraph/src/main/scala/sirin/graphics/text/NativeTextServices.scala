package sirin.graphics.text

import scala.annotation.unused
import sirin.{Committed, Lp, TextServices, TextStyle}
import sirin.graphics.{ParagraphOptions, WrappingLimits}
import sirin.graphics.unicode.NativeUnicode
import sirin.text.ResolvedTextLayout

/** Supplied static faces already carry the actual sizes. No family discovery,
  * implicit style fallback or font owner is introduced by this configuration.
  */
final case class NativeTextStyle(faces: Vector[NativeFont], options: ParagraphOptions = ParagraphOptions()):
  require(faces.nonEmpty && faces.distinct.size == faces.size && faces.size <= options.limits.maxFaces,
    "text style needs a bounded distinct nonempty face list")

/** Isolated real TextServices integration. Caller owns every supplied face and
  * Unicode service; this object opens/closes none. Preparation stays on its
  * creating Scala thread and follows the existing slow-work/phase policy.
  */
final class NativeTextServices(
    styles: Map[TextStyle, NativeTextStyle], unicode: NativeUnicode,
    wrappingLimits: WrappingLimits = WrappingLimits()
) extends TextServices:
  require(styles.nonEmpty, "text service needs at least one explicit style")
  private val owner = Thread.currentThread()

  override def layout(text: String, style: TextStyle, maxWidth: Lp)(using @unused committed: Committed): ResolvedTextLayout =
    require(Thread.currentThread() eq owner, "text service used from a different thread")
    val width = maxWidth.toDouble
    require(!width.isNaN && width >= 0, "text width must be nonnegative or positive infinity")
    val selected = styles.getOrElse(style, throw new IllegalArgumentException(s"unconfigured text style: $style"))
    if width == Double.PositiveInfinity then
      ResolvedTextLayout(NativeParagraph.layout(text, selected.faces, unicode, selected.options))
    else ResolvedTextLayout(NativeParagraph.wrap(text, selected.faces, unicode, width, selected.options, wrappingLimits))
