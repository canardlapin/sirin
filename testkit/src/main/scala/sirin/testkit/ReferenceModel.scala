package sirin.testkit

/** Deliberately non-incremental oracle: immutable vectors, recursive tree
  * interpretation and complete recomputation. No engine/scheduling imports.
  * Scope: static integer expressions and transactional writes/refusals/poison.
  */
object ReferenceModel:
  enum Expr:
    case Input(index: Int)
    case Literal(value: Int)
    case Plus(left: Expr, right: Expr)
    case Times(left: Expr, right: Expr)
    case Mod(input: Expr, modulus: Int)

  enum Edit:
    case Write(index: Int, value: Int)
    case Attempt(writes: Vector[(Int, Int)], refuse: Boolean)
    case Refuse
    case CaughtDefect

  enum Result:
    case Accepted, Refused, Failed

  final case class State(values: Vector[Int], revisions: Vector[Long],
      observations: Vector[(Int, Int)], pendingEffects: Vector[(Int, Int)])

  def evaluate(expression: Expr, cells: Vector[Int]): Int = expression match
    case Expr.Input(index) => cells(index)
    case Expr.Literal(value) => value
    case Expr.Plus(a, b) => evaluate(a, cells) + evaluate(b, cells)
    case Expr.Times(a, b) => evaluate(a, cells) * evaluate(b, cells)
    case Expr.Mod(a, modulus) => evaluate(a, cells) % modulus

  def initial(values: Vector[Int], roots: Vector[Expr]): State =
    val initial = roots.zipWithIndex.map((root, i) => (i, evaluate(root, values)))
    State(values, values.map(_ => 0L), initial, initial)

  def transact(state: State, roots: Vector[Expr], edits: Vector[Edit]): (State, Result) =
    var staged = state.values
    var poisoned = false
    var refused = false
    edits.foreach {
      case Edit.Write(index, value) if !refused => staged = staged.updated(index, value)
      case Edit.Attempt(writes, reject) if !refused =>
        if !reject then writes.foreach((index, value) => staged = staged.updated(index, value))
      case Edit.CaughtDefect if !refused => poisoned = true
      case Edit.Refuse => refused = true
      case _ => ()
    }
    if poisoned then (state, Result.Failed)
    else if refused then (state, Result.Refused)
    else
      val revisions = staged.indices.map(i => state.revisions(i) + (if staged(i) == state.values(i) then 0 else 1)).toVector
      val notifications = roots.zipWithIndex.flatMap { (root, index) =>
        val before = evaluate(root, state.values)
        val after = evaluate(root, staged)
        if before == after then None else Some((index, after))
      }
      (State(staged, revisions, state.observations ++ notifications, state.pendingEffects ++ notifications), Result.Accepted)
