package sirin.host

final case class FramePayload(tree: Node, logicalWidth: Double, logicalHeight: Double,
    pixelWidth: Int, pixelHeight: Int, scrolls: Map[Long,Double])
final case class FrameRequest(generation: Long, payload: FramePayload)

/** Owner-only generation gate. Immutable worker results never advance it. */
final class FrameCoordinator:
  private var serial = 0L
  private var current = Option.empty[FrameRequest]
  private var live = true
  def request(payload: FramePayload): Option[FrameRequest] =
    require(live,"host is retired")
    if current.exists(_.payload == payload) then None
    else
      serial=Math.addExact(serial,1)
      val next=FrameRequest(serial,payload)
      current=Some(next)
      Some(next)
  def accepts(request: FrameRequest): Boolean = live && current.contains(request)
  def retire(): Unit = { live=false; current=None }
