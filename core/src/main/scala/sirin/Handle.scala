package sirin

/** A generation-checked reference to a slot in a handle table.
  *
  * The slot index and generation are packed into one `Long` so that a handle
  * can cross the C boundary as `userdata` without referring to a managed
  * object. A handle whose generation no longer matches its slot is stale.
  */
opaque type Handle = Long

object Handle:
  def apply(slot: Int, generation: Int): Handle =
    (slot.toLong << 32) | (generation.toLong & 0xffffffffL)

  def fromBits(bits: Long): Handle = bits

  extension (h: Handle)
    def slot: Int       = (h >>> 32).toInt
    def generation: Int = h.toInt
    def bits: Long      = h
