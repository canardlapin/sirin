package sirin.testkit

/** Immutable public-behaviour oracle: transactional routing plus virtual time.
  * No engine, Signal, mutable transport or callback implementation dependency.
  */
object EventDeadlineModel:
  enum Op:
    case Emit(value: Int)
    case Input(value: Int)
    case Attempt(ops: Vector[Op], refuse: Boolean)
    case DisposeListener(index: Int)
    case DisposeSource, DisposeTimer, Refuse, Defect
  enum Step:
    case Batch(ops: Vector[Op])
    case Advance(ticks: Int)
    case Turn(budget: Int)
  enum Result:
    case Accepted, Refused, Failed
  final case class Job(listener: Int, value: Int)
  final case class State(input: Int = 0, output: Int = 0, now: Int = 0,
      deadline: Option[(Int, Int)] = None, source: Boolean = true, timer: Boolean = true,
      listeners: Vector[Boolean] = Vector(true, true), jobs: Vector[Job] = Vector.empty,
      received: Vector[(Int, Int)] = Vector.empty, cursor: Int = 0, activeTurns: Long = 0,
      units: Long = 0)
  final case class Receipt(result: Result = Result.Accepted, executed: Int = 0,
      failures: Vector[Result] = Vector.empty, exhausted: Boolean = false)
  def step(initial: State, step: Step, capacity: Int): (State, Receipt) = step match
    case Step.Advance(ticks) => initial.copy(now = initial.now + ticks) -> Receipt()
    case Step.Batch(ops) =>
      var draft = initial
      var emitted = Vector.empty[Int]
      var poisoned = false
      def run(ops: Vector[Op]): Result =
        var result = Result.Accepted
        ops.foreach { op =>
          if result == Result.Accepted then op match
            case Op.Emit(value) =>
              if !draft.source then { result = Result.Failed; poisoned = true }
              else if emitted.size >= capacity then result = Result.Refused
              else emitted :+= value
            case Op.Input(value) => draft = draft.copy(input = value)
            case Op.DisposeSource => draft = draft.copy(source = false)
            case Op.DisposeTimer => draft = draft.copy(timer = false)
            case Op.DisposeListener(index) => draft = draft.copy(listeners = draft.listeners.updated(index, false))
            case Op.Refuse => result = Result.Refused
            case Op.Defect => poisoned = true
            case Op.Attempt(inner, refuse) =>
              val saved = draft -> emitted
              val outcome = run(inner ++ (if refuse then Vector(Op.Refuse) else Vector.empty))
              if outcome == Result.Refused then { draft = saved._1; emitted = saved._2 }
              else if outcome == Result.Failed then result = outcome
        }
        result
      val outcome = run(ops)
      var result = if poisoned then Result.Failed else outcome
      if result == Result.Accepted then
        val pending = if !draft.source then Vector.empty else initial.jobs.filter(job => draft.listeners(job.listener))
        val added = if !draft.source then Vector.empty else emitted.flatMap(value =>
          draft.listeners.indices.filter(draft.listeners).map(index => Job(index, value)))
        if pending.size + added.size > capacity then result = Result.Refused
        else
          val deadline = if !draft.timer then None
            else if initial.input == draft.input then initial.deadline
            else if draft.input == initial.output then None
            else Some((initial.now + 3, draft.input))
          draft = draft.copy(jobs = pending ++ added, deadline = deadline)
      (if result == Result.Accepted then draft else initial) -> Receipt(result)
    case Step.Turn(budget) =>
      var state = initial
      var count = 0
      var empty = 0
      var failures = Vector.empty[Result]
      while count < budget && empty < 5 do
        val kind = state.cursor
        state = state.copy(cursor = (kind + 1) % 5)
        var executed = false
        if kind == 0 && state.jobs.nonEmpty then
          val job = state.jobs.head
          state = state.copy(jobs = state.jobs.tail)
          executed = true
          val selected = job.listener == 0 || job.value % 2 != 0
          if selected then
            if job.value % 5 == 0 then failures :+= Result.Refused
            else if job.value % 7 == 0 then failures :+= Result.Failed
            else state = state.copy(received = state.received :+ (job.listener -> (job.value * (if job.listener == 0 then 1 else 10))))
        else if kind == 1 && state.deadline.exists(_._1 <= state.now) then
          state = state.copy(output = state.deadline.get._2, deadline = None)
          executed = true
        if executed then { count += 1; empty = 0 } else empty += 1
      val ready = state.jobs.nonEmpty || state.deadline.exists(_._1 <= state.now)
      state.copy(activeTurns = state.activeTurns + (if count > 0 then 1 else 0), units = state.units + count) ->
        Receipt(executed = count, failures = failures, exhausted = count == budget && ready)
