package sirin.desktop

import sirin.host.HostInput

/** Ordered owner facts: a close request cannot overtake preceding edits or geometry. */
private[desktop] final class OwnerInputQueue[A](capacity: Int=256):
  require(capacity>0)
  private val pending=scala.collection.mutable.Queue.empty[(A,Option[HostInput])]
  private var closePending=false
  def closing: Boolean=closePending
  def nonEmpty: Boolean=pending.nonEmpty
  def clear(): Unit={pending.clear();closePending=false}
  def add(context: A,input: Option[HostInput]): Unit =
    require(!closePending && pending.size<capacity,"window essential-input budget/order exceeded")
    pending.enqueue(context -> input)
    if input.isEmpty then closePending=true
  /** False means a dispatched fact retired this peer. */
  def drain(ready: () => Boolean,needsGeometry: HostInput => Boolean)(
      input: (A,HostInput) => Boolean,close: A => Boolean): Boolean =
    var consume=true
    while consume && pending.nonEmpty do
      val (context,value)=pending.front
      value match
        case Some(event) if needsGeometry(event) && !ready() => consume=false
        case _ =>
          pending.dequeue()
          val alive=value match
            case Some(event) => input(context,event)
            case None => closePending=false;close(context)
          if !alive then {clear();return false}
    true


/** Native commands observe all earlier transported owner input, including layout waits. */
private[desktop] final class OwnerCommandBoundary:
  private var quitPending=false
  def requestQuit(): Unit=quitPending=true
  def drain(inputPending: Boolean)(quit: () => Unit,menus: () => Unit): Unit =
    if !inputPending then
      if quitPending then
        quitPending=false
        quit()
      menus()
