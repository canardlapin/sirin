package sirin.testkit

/** Recomputes latest-generation state without engine or Signal dependencies. */
object LatestModel:
  final case class Completed(input: Int, output: Int, current: Boolean)
  enum Display:
    case Failed
    case Pending(input: Int, previous: Option[Completed])
    case Done(result: Completed)
  enum Op:
    case Input(divisor: Int)
    case Attempt(ops: Vector[Op], refuse: Boolean)
    case Refuse, Defect, Dispose
  enum Step:
    case Batch(ops: Vector[Op])
    case Run(index: Int)
    case Deliver(index: Int)
  enum Result:
    case Accepted, Refused, Failed
  final case class Job(epoch: Int, input: Int, requested: Boolean = false, done: Boolean = false)
  final case class State(divisor: Int = 1, input: Option[Int] = Some(10), epoch: Int = 1,
      accepted: Int = 0, shown: Option[Completed] = None, alive: Boolean = true, waiting: Boolean = false,
      display: Display = Display.Pending(10, None), jobs: Vector[Job] = Vector(Job(1, 10)),
      executed: Vector[(Int, Boolean)] = Vector.empty)
  private def pick[A](xs: Vector[A], index: Int): Option[A] = xs.lift(if xs.isEmpty then 0 else Math.floorMod(index, xs.size))
  private def start(state: State): State =
    if state.waiting && state.jobs.size < 3 then state.copy(waiting = false, jobs = state.jobs :+ Job(state.epoch, state.input.get))
    else state
  def step(state: State, step: Step, progressive: Boolean): (State, Result) = step match
    case Step.Batch(ops) =>
      var divisor = state.divisor
      var alive = state.alive
      var poisoned = false
      def run(ops: Vector[Op]): Result =
        var result = Result.Accepted
        ops.foreach { op => if result == Result.Accepted then op match
          case Op.Input(value) => if alive then divisor = value else result = Result.Failed
          case Op.Attempt(inner, refuse) =>
            val saved = divisor
            val savedAlive = alive
            val nested = run(inner ++ (if refuse then Vector(Op.Refuse) else Vector.empty))
            if nested == Result.Refused then { divisor = saved; alive = savedAlive }
            else if nested == Result.Failed then result = Result.Failed
          case Op.Refuse => result = Result.Refused
          case Op.Defect => poisoned = true
          case Op.Dispose => alive = false
        }
        result
      val outcome = run(ops)
      val result = if poisoned then Result.Failed else outcome
      if result != Result.Accepted then (state, result)
      else if !alive then (state.copy(divisor = divisor, alive = false, jobs = Vector.empty, waiting = false), result)
      else
        val input = Option.when(divisor != 0)(10 / divisor)
        if input == state.input then (state.copy(divisor = divisor), result)
        else
          val shown = state.shown.map(_.copy(current = false))
          val display = input.fold[Display](Display.Failed)(Display.Pending(_, shown))
          (start(state.copy(divisor = divisor, input = input, epoch = state.epoch + 1, waiting = input.nonEmpty,
            display = display, jobs = state.jobs.map(_.copy(requested = true)))), result)
    case Step.Run(index) => pick(state.jobs.filterNot(_.done), index) match
      case None => (state, Result.Accepted)
      case Some(job) => (state.copy(jobs = state.jobs.map(j => if j.epoch == job.epoch then j.copy(done = true) else j),
        executed = state.executed :+ (job.input -> job.requested)), Result.Accepted)
    case Step.Deliver(index) => pick(state.jobs.filter(_.done), index) match
      case None => (state, Result.Accepted)
      case Some(job) =>
        val next = start(state.copy(jobs = state.jobs.filterNot(_.epoch == job.epoch)))
        val current = job.epoch == state.epoch
        if state.input.nonEmpty && job.epoch > state.accepted && (current || progressive) then
          val completed = Completed(job.input, job.input * 10, current)
          val display = if current then Display.Done(completed) else Display.Pending(state.input.get, Some(completed))
          (next.copy(accepted = job.epoch, shown = Some(completed), display = display), Result.Accepted)
        else (next, Result.Accepted)
