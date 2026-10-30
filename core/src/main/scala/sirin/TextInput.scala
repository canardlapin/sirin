package sirin

/** A UTF-16 range in an element's own text. `anchor` may follow `focus`; the range is then backward. */
final case class TextRange(anchor: Int, focus: Int):
  require(anchor >= 0 && focus >= 0, "text range offsets must be nonnegative")
  def start: Int = math.min(anchor, focus)
  def end: Int = math.max(anchor, focus)
  def isEmpty: Boolean = anchor == focus

object TextRange:
  def at(offset: Int): TextRange = TextRange(offset, offset)

/** The text channel, separate from physical and logical keys (VISION §6). Offsets are UTF-16 and already normalized by
  * the host; platform codepoint indices never reach an element.
  */
enum TextInputEvent:
  /** Final text from typing, an IME commit or dictation. It replaces any active composition. */
  case Commit(text: String)

  /** Provisional IME text with a selection inside it. Empty text ends the composition without committing. */
  case Compose(text: String, selection: TextRange)

  /** The platform abandoned the composition, for example on Escape, focus loss or presentation loss. */
  case CancelComposition

  /** Assistive technology asked to select a range of the exposed text. */
  case Select(range: TextRange)

  /** Assistive technology asked to replace the exposed value. */
  case Replace(text: String)

/** Declared by a focused element that accepts text. The host enables platform text input only while one is declared,
  * and positions the IME candidate window at `caret`, local to the element and clipped to its presented bounds.
  */
final case class TextInputRequest(caret: Rect)

/** Platform text services for one dispatched handler. Clipboard writes and composition cancellation are staged: they
  * take effect only if the handler's transaction commits.
  */
trait TextEditingServices:
  /** Original UTF-16 grapheme boundaries, including 0 and the text length. */
  def graphemeBoundaries(text: String): Vector[Int]

  /** Word segments, excluding whitespace and punctuation, as forward UTF-16 ranges on grapheme boundaries. */
  def words(text: String): Vector[TextRange]

  /** None when the clipboard is unavailable. */
  def clipboard: Option[String]
  def copy(text: String): Unit
  def cancelComposition(): Unit

  /** The layout prepared for this element's request in the frame the event was routed against. Lookup only. */
  def presented(request: TextRequest): Option[TextLayout]

object TextEditingServices:
  /** For dispatch paths without a text platform. Segmentation and copying refuse rather than guess. */
  val unavailable: TextEditingServices =
    new TextEditingServices:
      def graphemeBoundaries(text: String): Vector[Int] =
        throw new UnsupportedOperationException("text segmentation is unavailable on this dispatch path")
      def words(text: String): Vector[TextRange] =
        throw new UnsupportedOperationException("text segmentation is unavailable on this dispatch path")
      def clipboard: Option[String] = None
      def copy(text: String): Unit =
        throw new UnsupportedOperationException("the clipboard is unavailable on this dispatch path")
      def cancelComposition(): Unit = ()
      def presented(request: TextRequest): Option[TextLayout] = None

/** Text exposed to assistive technology for one semantic node. The host resolves `request` from the element's prepared
  * text and places it at `origin`, local to the element. A composing control exposes its displayed text.
  */
final case class TextSemantics(
    request: TextRequest,
    origin: Point,
    selection: TextRange,
    multiline: Boolean = false,
    editable: Boolean = true
):
  require(
    selection.end <= request.content.length,
    "text semantics selection must lie inside the exposed text"
  )
