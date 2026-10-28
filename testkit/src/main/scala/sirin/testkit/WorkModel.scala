package sirin.testkit

/** Full-state scheduling oracle. No engine or Signal imports. */
object WorkModel:
  enum Mode:
    case Parallel, Serial, Refuse, Supersede
  enum Accept:
    case Latest, Progressive, Every, Snapshot
  enum End:
    case Success(value: Int)
    case Failure, Cancelled
  enum Op:
    case Dispatch(token: Int)
    case Cancel(index: Int)
    case Enable(value: Boolean)
    case Attempt(ops: Vector[Op], refuse: Boolean)
    case Refuse, Defect, Dispose
  enum Step:
    case Batch(ops: Vector[Op])
    case Run(index: Int)
    case Deliver(index: Int)
    case Drain(budget: Int)
  enum Trace:
    case Running(count: Int)
    case Last(token: Int, current: Boolean, end: End)
    case Terminal(token: Int, current: Boolean, end: End)
    case Applied(token: Int, current: Boolean)
    case Executed(token: Int, cancellation: Boolean)
    case Effect(count: Int)
  enum Result:
    case Accepted, Refused, Failed
  final case class Job(token: Int, generation: Int, aborted: Boolean = false,
      requested: Boolean = false, ready: Boolean = false, end: Option[End] = None)
  final case class State(alive: Boolean = true, enabled: Boolean = true, generation: Int = 0,
      jobs: Vector[Job] = Vector.empty, last: Option[(Int, Boolean, End)] = None,
      refusal: Option[String] = None, pending: Vector[Int] = Vector(0),
      trace: Vector[Trace] = Vector(Trace.Running(0)))
  private def select[A](xs: Vector[A], index: Int): Option[A] = xs.lift(if xs.isEmpty then 0 else Math.floorMod(index, xs.size))
  private def ready(jobs: Vector[Job], mode: Mode): Vector[Job] = jobs.zipWithIndex.map((j, i) => j.copy(ready = mode != Mode.Serial || i == 0 || j.ready))
  def batch(state: State, ops: Vector[Op], mode: Mode): (State, Result) =
    var working = state
    var poisoned = false
    def edits(ops: Vector[Op]): Result =
      var result = Result.Accepted
      ops.foreach { op => if result == Result.Accepted then
        if !working.alive then result = Result.Failed
        else op match
          case Op.Dispatch(token) =>
            val reason = if !working.enabled then Some("command disabled")
              else if mode == Mode.Refuse && working.jobs.nonEmpty then Some("command busy")
              else if mode == Mode.Serial && working.jobs.size >= 3 then Some("command queue full")
              else if working.jobs.size >= 4 then Some("work capacity full") else None
            reason match
              case Some(message) => working = working.copy(refusal = Some(message))
              case None =>
                val gen = working.generation + 1
                val older = if mode == Mode.Supersede then working.jobs.map(_.copy(requested = true)) else working.jobs
                working = working.copy(generation = gen, refusal = None, jobs = older :+ Job(token, gen))
          case Op.Cancel(index) => select(working.jobs, index).foreach { chosen =>
            val queued = mode == Mode.Serial && working.jobs.head != chosen
            working = working.copy(jobs = working.jobs.map(j => if j.token == chosen.token then
              j.copy(aborted = true, requested = true, end = if queued && j.end.isEmpty then Some(End.Cancelled) else j.end) else j))
          }
          case Op.Enable(value) => working = working.copy(enabled = value)
          case Op.Attempt(inner, refuse) =>
            val saved = working
            val nested = edits(inner ++ (if refuse then Vector(Op.Refuse) else Vector.empty))
            if nested == Result.Refused then working = saved
            else if nested == Result.Failed then result = Result.Failed
          case Op.Refuse => result = Result.Refused
          case Op.Defect => poisoned = true
          case Op.Dispose => working = working.copy(alive = false)
      }
      result
    val outcome = edits(ops)
    val result = if poisoned then Result.Failed else outcome
    if result != Result.Accepted then (state, result)
    else if !working.alive then (working.copy(jobs = Vector.empty, pending = Vector.empty), result)
    else
      val changed = working.jobs.size != state.jobs.size
      val trace = state.trace ++ (if changed then Vector(Trace.Running(working.jobs.size)) else Vector.empty)
      val pending = state.pending ++ (if changed then Vector(working.jobs.size) else Vector.empty)
      (working.copy(jobs = ready(working.jobs, mode), trace = trace, pending = pending), result)

  def step(state: State, step: Step, mode: Mode, accept: Accept): (State, Result) = step match
    case Step.Batch(ops) => batch(state, ops, mode)
    case Step.Run(index) => select(state.jobs.filter(j => j.ready && j.end.isEmpty), index) match
      case None => (state, Result.Accepted)
      case Some(job) =>
        val end = if job.token % 13 == 0 then End.Failure else End.Success(job.token * 10)
        (state.copy(jobs = state.jobs.map(j => if j.token == job.token then j.copy(end = Some(end)) else j),
          trace = state.trace :+ Trace.Executed(job.token, job.requested)), Result.Accepted)
    case Step.Deliver(index) => select(state.jobs.filter(_.end.nonEmpty), index) match
      case None => (state, Result.Accepted)
      case Some(job) =>
        val current = job.generation == state.generation
        val accepted = !job.aborted && (accept != Accept.Latest || current)
        val end = job.end.get
        val remaining = ready(state.jobs.filterNot(_.token == job.token), mode)
        val terminal = Vector(Trace.Running(remaining.size)) ++
          (if accepted then Vector(Trace.Last(job.token, current, end)) else Vector.empty) :+
          Trace.Terminal(job.token, current, end)
        val refused = accepted && job.token % 7 == 0
        val defect = accepted && job.token % 11 == 0
        val apply = accepted && !refused && !defect
        val trace = state.trace ++ terminal ++ (if apply then Vector(Trace.Applied(job.token, current)) else Vector.empty)
        val result = if defect then Result.Failed else if refused then Result.Refused else Result.Accepted
        (state.copy(jobs = remaining, last = if accepted then Some((job.token, current, end)) else state.last,
          pending = state.pending :+ remaining.size, trace = trace), result)
    case Step.Drain(budget) =>
      val jobs = state.pending.take(budget)
      (state.copy(pending = state.pending.drop(budget), trace = state.trace ++ jobs.map(Trace.Effect(_))), Result.Accepted)
