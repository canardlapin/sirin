package sirin.desktop

/** Native-window activation history; logical control focus remains owned by each peer. */
private[desktop] final class WindowActivationOrder:
  private var recent = Vector.empty[Long]

  def observe(live: Vector[Long], focused: Option[Long]): Unit =
    recent = recent.filter(live.contains)
    focused.filter(live.contains).foreach(id => recent = id +: recent.filterNot(_ == id))

  def afterRetirement(
      live: Vector[Long],
      removedFocused: Boolean,
      focusedNow: Option[Long],
      quitting: Boolean
  ): Option[Long] =
    val current = focusedNow.filter(live.contains)
    val next =
      if removedFocused && current.isEmpty && !quitting then
        recent.find(live.contains).orElse(live.lastOption)
      else None
    observe(live, current.orElse(next))
    next
