package sirin.host

import sirin.*
import sirin.engine.ElementFrame

/** Render-owner bounded size cache. Keys contain no frame, handler or scope. */
final class ElementMeasureCache(val capacity: Int=1024):
  require(capacity>0 && capacity<=10000,"invalid element measurement cache capacity")
  private val entries=scala.collection.mutable.LinkedHashMap.empty[(AnyRef,Constraints),Size]
  def size: Int=entries.size
  def measure(frame: ElementFrame,constraints: Constraints): Size =
    val key=(frame.layoutIdentity,constraints)
    entries.remove(key) match
      case Some(size) => entries(key)=size;size
      case None =>
        val size=frame.measure(constraints)
        while entries.size>=capacity do entries.remove(entries.head._1)
        entries(key)=size;size
