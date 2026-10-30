package sirin.host

import sirin.*
import sirin.engine.ElementFrame

/** Render-owner bounded size cache. Keys contain no frame, handler or scope. */
final class ElementMeasureCache(val capacity: Int = 1024):
  require(capacity > 0 && capacity <= 10000, "invalid element measurement cache capacity")
  private val entries = scala
    .collection
    .mutable
    .LinkedHashMap
    .empty[(AnyRef, Constraints, Vector[(PhaseDependency, Long)]), Size]
  def size: Int = entries.size
  def measure(frame: ElementFrame, constraints: Constraints, epochs: PhaseEpochs = PhaseEpochs())(
      compute: => Size
  ): Size =
    val globals = frame.layoutGlobals.toVector.sortBy(_.ordinal).map(d => d -> epochs(d))
    val key = (frame.layoutIdentity, constraints, globals)
    entries.remove(key) match
      case Some(size) =>
        entries(key) = size
        size
      case None =>
        val size = compute
        while entries.size >= capacity do
          entries.remove(entries.head._1)
        entries(key) = size
        size
