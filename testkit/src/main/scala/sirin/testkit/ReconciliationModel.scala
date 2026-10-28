package sirin.testkit

/** Full-state oracle. No engine, Signal or reconciliation implementation imports. */
object ReconciliationModel:
  final case class Item(key: Int, label: Int)
  final case class Live(generation: Int, item: Item, draft: Int)
  enum Op:
    case Assign(items: Vector[Item])
    case Draft(key: Int, value: Int)
    case Read(key: Int)
    case Attempt(ops: Vector[Op], refuse: Boolean)
    case Rebuild, Refuse, CaughtDefect
  enum Step:
    case Batch(ops: Vector[Op])
    case Drain(budget: Int)
    case Deliver(generation: Int)
  enum Result:
    case Accepted, Refused, Failed
  enum Trace:
    case Order(rows: Vector[(Int, Int, Int)]) // key, label, generation
    case Observed(generation: Int, key: Int, label: Int, draft: Int)
    case Effect(generation: Int, key: Int, label: Int, draft: Int)
    case Released(generation: Int)
  final case class State(source: Vector[Item], revision: Long, live: Vector[Live], nextGeneration: Int,
      pending: Vector[Live], trace: Vector[Trace])
  final case class Receipt(result: Result, reads: Vector[Int])
  val initial: State = State(Vector.empty, 0L, Vector.empty, 1, Vector.empty, Vector(Trace.Order(Vector.empty)))
  private def order(rows: Vector[Live]): Vector[(Int, Int, Int)] = rows.map(r => (r.item.key, r.item.label, r.generation))

  def transact(state: State, ops: Vector[Op]): (State, Receipt) =
    var source = state.source
    var drafts = state.live.map(r => r.item.key -> r.draft).toMap
    var poisoned = false
    var reads = Vector.empty[Int]
    def run(edits: Vector[Op]): Result =
      var result = Result.Accepted
      edits.foreach { op => if result == Result.Accepted then op match
        case Op.Assign(items) =>
          if items.map(_.key).distinct.size != items.size then result = Result.Failed
          else source = items
        case Op.Draft(key, value) =>
          if !drafts.contains(key) then result = Result.Failed else drafts = drafts.updated(key, value)
        case Op.Read(key) =>
          if !drafts.contains(key) || !source.exists(_.key == key) then result = Result.Failed
          else reads :+= source.find(_.key == key).get.label
        case Op.Attempt(inner, refuse) =>
          val savedSource = source
          val savedDrafts = drafts
          val nested = run(inner ++ (if refuse then Vector(Op.Refuse) else Vector.empty))
          if nested == Result.Refused then { source = savedSource; drafts = savedDrafts }
          else if nested == Result.Failed then result = Result.Failed
        case Op.Rebuild => ()
        case Op.Refuse => result = Result.Refused
        case Op.CaughtDefect => poisoned = true
      }
      result
    val outcome = run(ops)
    val result = if poisoned || outcome == Result.Failed then Result.Failed else outcome
    if result != Result.Accepted then (state, Receipt(result, reads))
    else
      var nextId = state.nextGeneration
      val next = source.map { item => state.live.find(_.item.key == item.key) match
        case Some(old) => old.copy(item = item, draft = drafts(item.key))
        case None =>
          val mounted = Live(nextId, item, 0)
          nextId += 1
          mounted
      }
      val changed = next.filter(row => !state.live.contains(row)).sortBy(_.generation)
      val alive = next.map(_.generation).toSet
      val removed = state.live.filterNot(r => alive(r.generation)).sortBy(r => -r.generation)
      val structural = if order(state.live) == order(next) then Vector.empty else Vector(Trace.Order(order(next)))
      val observations = changed.map(r => Trace.Observed(r.generation, r.item.key, r.item.label, r.draft))
      val releases = removed.map(r => Trace.Released(r.generation))
      val pending = state.pending.filter(r => alive(r.generation)) ++ changed
      (State(source, state.revision + (if source == state.source then 0 else 1), next, nextId, pending,
        state.trace ++ structural ++ observations ++ releases), Receipt(Result.Accepted, reads))

  def drain(state: State, budget: Int): (State, Int) =
    val jobs = state.pending.take(budget)
    val trace = jobs.map(r => Trace.Effect(r.generation, r.item.key, r.item.label, r.draft))
    (state.copy(pending = state.pending.drop(budget), trace = state.trace ++ trace), jobs.size)
  def deliver(state: State, generation: Int): (State, Receipt) = state.live.find(_.generation == generation) match
    case None => (state, Receipt(Result.Refused, Vector.empty))
    case Some(row) => transact(state, Vector(Op.Draft(row.item.key, row.draft + 1)))
