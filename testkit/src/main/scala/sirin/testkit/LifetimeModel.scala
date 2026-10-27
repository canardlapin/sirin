package sirin.testkit

/** Independent full-state lifecycle interpreter. No engine, signals or shared
  * scheduling code: branch values are computed directly from integer vectors.
  */
object LifetimeModel:
  enum Op:
    case Global(index: Int, value: Int)
    case Draft(token: Int, value: Int)
    case Mount(token: Int, parent: Int, initial: Int)
    case Dispose(token: Int)
    case Attempt(ops: Vector[Op], refuse: Boolean)
    case Refuse, CaughtDefect
  enum Step:
    case Batch(ops: Vector[Op])
    case Drain(budget: Int)
    case Deliver(token: Int)
  enum Result:
    case Accepted, Refused, Failed
  enum Trace:
    case Observed(token: Int, value: Int)
    case EffectRan(token: Int, value: Int)
    case Released(token: Int)
  final case class Owner(parent: Int, draft: Int, revision: Long)
  final case class State(globals: Vector[Int], revisions: Vector[Long], owners: Map[Int, Owner],
      order: Vector[Int], pending: Vector[(Int, Int)], trace: Vector[Trace])
  val initial: State = State(Vector(0, 7, 7), Vector(0L, 0L, 0L), Map.empty, Vector.empty, Vector.empty, Vector.empty)
  def value(state: State, token: Int): Int =
    val branch = if state.globals(0) % 2 == 0 then state.globals(1) else state.globals(2)
    branch + state.owners(token).draft

  def transact(state: State, ops: Vector[Op]): (State, Result) =
    var candidate = state
    var poison = false
    var compensated = Vector.empty[Int]
    // All registrations transfer one lease. Track even a provisional mount
    // that is disposed before a later root refusal.
    var acquired = Vector.empty[Int]
    def rollback(saved: State, leaseMark: Int): Unit =
      compensated ++= acquired.drop(leaseMark).reverse
      acquired = acquired.take(leaseMark)
      candidate = saved
    def run(edits: Vector[Op]): Result =
      var result = Result.Accepted
      edits.foreach { op => if result == Result.Accepted then op match
        case Op.Global(index, v) => candidate = candidate.copy(globals = candidate.globals.updated(index, v))
        case Op.Draft(token, v) => candidate.owners.get(token) match
          case None => result = Result.Failed
          case Some(o) => candidate = candidate.copy(owners = candidate.owners.updated(token, o.copy(draft = v)))
        case Op.Mount(token, parent, v) =>
          if candidate.owners.contains(token) || (parent != 0 && !candidate.owners.contains(parent)) then result = Result.Failed
          else
            acquired :+= token
            candidate = candidate.copy(owners = candidate.owners.updated(token, Owner(parent, v, 0L)), order = candidate.order :+ token)
        case Op.Dispose(token) =>
          def under(t: Int): Boolean = t == token || (candidate.owners(t).parent != 0 && under(candidate.owners(t).parent))
          val removed = candidate.owners.keys.filter(under).toSet
          candidate = candidate.copy(owners = candidate.owners -- removed, order = candidate.order.filterNot(removed))
        case Op.Attempt(inner, refuse) =>
          val saved = candidate
          val mark = acquired.size
          val innerResult = run(inner ++ (if refuse then Vector(Op.Refuse) else Vector.empty))
          if innerResult == Result.Refused then rollback(saved, mark)
          else if innerResult == Result.Failed then result = Result.Failed
        case Op.Refuse => result = Result.Refused
        case Op.CaughtDefect => poison = true
      }
      result
    val execution = run(ops)
    val result = if poison || execution == Result.Failed then Result.Failed else execution
    if result != Result.Accepted then
      rollback(state, 0)
      (state.copy(trace = state.trace ++ compensated.map(Trace.Released(_))), result)
    else
      val globalsRevisions = candidate.globals.indices.map(i => state.revisions(i) + (if state.globals(i) == candidate.globals(i) then 0 else 1)).toVector
      val owners = candidate.owners.map { (token, o) =>
        val revision = state.owners.get(token).fold(0L)(old => old.revision + (if old.draft == o.draft then 0 else 1))
        token -> o.copy(revision = revision)
      }
      candidate = candidate.copy(revisions = globalsRevisions, owners = owners)
      val changes = candidate.order.flatMap { token =>
        if !state.owners.contains(token) || value(candidate, token) != value(state, token) then Some((token, value(candidate, token)))
        else None
      }
      val retired = (state.order ++ acquired).filterNot(candidate.owners.contains).distinct.reverse
      val pending = state.pending.filter((token, _) => candidate.owners.contains(token)) ++ changes
      val trace = state.trace ++ compensated.map(Trace.Released(_)) ++ changes.map((t, v) => Trace.Observed(t, v)) ++ retired.map(Trace.Released(_))
      (candidate.copy(pending = pending, trace = trace), Result.Accepted)

  def drain(state: State, budget: Int): (State, Int) =
    val jobs = state.pending.filter((token, _) => state.owners.contains(token))
    val run = jobs.take(budget)
    (state.copy(pending = jobs.drop(budget), trace = state.trace ++ run.map((t, v) => Trace.EffectRan(t, v))), run.size)

  def deliver(state: State, token: Int): (State, Result) = state.owners.get(token) match
    case None => (state, Result.Refused)
    case Some(o) => transact(state, Vector(Op.Draft(token, o.draft + 1)))
