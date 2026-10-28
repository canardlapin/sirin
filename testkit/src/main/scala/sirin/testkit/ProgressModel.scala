package sirin.testkit

/** Immutable queue oracle. Slot histories represent tentative UI alternatives;
  * worker receipts persist when a UI branch is discarded. No engine dependency.
  */
object ProgressModel:
  enum Op:
    case Offer(value: Int)
    case Produce(value: Int)
    case Attempt(ops: Vector[Op], refuse: Boolean)
    case Close, Cancel, Refuse, Defect
  enum Step:
    case Batch(ops: Vector[Op])
    case Produce(value: Int)
    case Drain(budget: Int, reject: Boolean)
  enum Result:
    case Accepted, Refused, Failed
  final case class Item(value: Int, frame: Int = 0)
  final case class State(slots: Vector[Vector[Item]] = Vector.empty, closed: Boolean = false,
      cancelled: Option[Int] = None, accepted: Long = 0, delivered: Long = 0,
      coalesced: Long = 0, abandoned: Long = 0, received: Vector[Int] = Vector.empty):
    def status: String = cancelled.fold(if closed then
      if slots.isEmpty then "Drained" else s"Closing(${slots.size})"
      else "Open")(n => s"Cancelled($n)")
  final case class Receipt(result: Result = Result.Accepted, offers: Vector[String] = Vector.empty,
      drained: Int = 0, failure: Option[Result] = None)
  def step(initial: State, step: Step, latest: Boolean, capacity: Int): (State, Receipt) =
    var state = initial
    var offers = Vector.empty[String]
    var close = initial.closed
    var cancel = initial.cancelled.nonEmpty
    def offer(value: Int, frame: Int): Unit =
      val worker = frame == 0
      val refused = if (if worker then state.cancelled.nonEmpty else cancel) then Some("Cancelled")
        else if (if worker then state.closed else close) then Some("Closed") else None
      val room = state.slots.size < capacity
      val replace = latest && state.slots.lastOption.exists(slot => worker && slot.last.frame == 0 || !worker)
      val result = refused.getOrElse(if room || replace then "Accepted" else "Full")
      offers :+= result
      if result == "Accepted" then
        val next = Item(value, frame)
        val slots = if room then state.slots :+ Vector(next)
          else if worker then state.slots.init :+ Vector(next)
          else state.slots.init :+ (state.slots.last :+ next)
        state = state.copy(slots = slots, accepted = state.accepted + (if worker then 1 else 0),
          coalesced = state.coalesced + (if worker && !room then 1 else 0))
    def discard(from: Int): Unit = state = state.copy(slots = state.slots.map(_.filter(_.frame < from)).filter(_.nonEmpty))
    step match
      case Step.Produce(value) => offer(value, 0); state -> Receipt(offers = offers)
      case Step.Batch(ops) =>
        var serial = 1
        var poisoned = false
        def run(ops: Vector[Op], frame: Int): Result =
          var result = Result.Accepted
          ops.foreach { op => if result == Result.Accepted then op match
            case Op.Offer(value) => offer(value, frame)
            case Op.Produce(value) => offer(value, 0)
            case Op.Close => if state.cancelled.isEmpty && !(state.closed && state.slots.isEmpty) then close = true
            case Op.Cancel => if state.cancelled.isEmpty && !(state.closed && state.slots.isEmpty) then cancel = true
            case Op.Refuse => result = Result.Refused
            case Op.Defect => poisoned = true
            case Op.Attempt(inner, refuse) =>
              serial += 1
              val nestedFrame = serial
              val saved = close -> cancel
              val outcome = run(inner ++ (if refuse then Vector(Op.Refuse) else Vector.empty), nestedFrame)
              if outcome == Result.Refused then
                discard(nestedFrame)
                close = saved._1
                cancel = saved._2
          }
          result
        val outcome = run(ops, 1)
        val result = if poisoned then Result.Failed else outcome
        if result != Result.Accepted then discard(1)
        else
          val newAccepted = state.slots.flatten.count(_.frame != 0)
          val newCoalesced = state.slots.map(_.size - 1).sum
          state = state.copy(slots = state.slots.map(slot => Vector(slot.last.copy(frame = 0))),
            accepted = state.accepted + newAccepted, coalesced = state.coalesced + newCoalesced, closed = close)
          if cancel && state.cancelled.isEmpty then
            state = state.copy(cancelled = Some(state.slots.size), abandoned = state.abandoned + state.slots.size, slots = Vector.empty)
        state -> Receipt(result, offers)
      case Step.Drain(budget, reject) =>
        var count = 0
        var failure = Option.empty[Result]
        while count < budget && state.slots.nonEmpty && failure.isEmpty do
          val value = state.slots.head.last.value
          if reject && value % 5 == 0 then failure = Some(Result.Refused)
          else if reject && value % 7 == 0 then failure = Some(Result.Failed)
          else
            state = state.copy(slots = state.slots.tail, delivered = state.delivered + 1, received = state.received :+ value)
            count += 1
        state -> Receipt(drained = count, failure = failure)
