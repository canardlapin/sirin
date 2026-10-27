package sirin.testkit

import sirin.*
import sirin.engine.*
import sirin.testkit.ReferenceModel.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import scala.collection.mutable.ArrayBuffer

class ReferenceAgreementSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(300)
  private val value = Gen.choose(-8, 8)
  private def expression(depth: Int): Gen[Expr] =
    val leaf = Gen.oneOf(Gen.choose(0, 2).map(Expr.Input(_)), value.map(Expr.Literal(_)))
    if depth == 0 then leaf
    else Gen.frequency(
      3 -> leaf,
      2 -> (for a <- expression(depth - 1); b <- expression(depth - 1) yield Expr.Plus(a, b)),
      2 -> (for a <- expression(depth - 1); b <- expression(depth - 1) yield Expr.Times(a, b)),
      1 -> expression(depth - 1).map(Expr.Mod(_, 3)),
      3 -> (for c <- leaf; a <- expression(depth - 1); b <- expression(depth - 1) yield Expr.Choose(c, a, b)))
  private val write = for index <- Gen.choose(0, 2); v <- value yield (index, v)
  private val edit: Gen[Edit] = Gen.frequency(
    6 -> write.map((i, v) => Edit.Write(i, v)),
    2 -> (for writes <- Gen.listOfN(3, write); refuse <- Gen.oneOf(true, false) yield Edit.Attempt(writes.toVector, refuse)),
    1 -> Gen.const(Edit.Refuse), 1 -> Gen.const(Edit.CaughtDefect))
  private val program = for
    initial <- Gen.listOfN(3, value)
    roots <- Gen.listOfN(3, expression(3))
    transactions <- Gen.listOfN(30, Gen.listOfN(5, edit))
  yield (initial.toVector, roots.toVector, transactions.map(_.toVector).toVector)

  private def compile(expr: Expr, cells: Vector[Cell[Int]]): Signal[Int] = expr match
    case Expr.Input(i) => cells(i)
    case Expr.Literal(value) => Signal.const(value)
    case Expr.Plus(a, b) => compile(a, cells).zip(compile(b, cells)).map(_ + _)
    case Expr.Times(a, b) => compile(a, cells).zip(compile(b, cells)).map(_ * _)
    case Expr.Mod(a, n) => compile(a, cells).map(_ % n)
    case Expr.Choose(c, a, b) =>
      val even = compile(a, cells)
      val odd = compile(b, cells)
      compile(c, cells).flatMap(v => if v % 2 == 0 then even else odd)

  property("randomized transactions match independent recomputation, ordered observations and effect counts") {
    Prop.forAll(program) { (initial, expressions, transactions) =>
      val runtime = new HeadlessRuntime
      val cells = initial.map(runtime.cell(_))
      val signals = expressions.map(compile(_, cells))
      val observations = ArrayBuffer.empty[(Int, Int)]
      val effects = ArrayBuffer.empty[(Int, Int)]
      signals.zipWithIndex.foreach { (signal, index) =>
        runtime.observe(signal) {
          case Value.Ok(value) => observations += ((index, value))
          case Value.Failed(error) => throw error
        }
        runtime.effect(signal)(value => effects += ((index, value)))
      }
      var reference = ReferenceModel.initial(initial, expressions)
      var agrees = true
      transactions.foreach { edits =>
        val (next, expected) = ReferenceModel.transact(reference, expressions, edits)
        val actual = runtime.transact {
          edits.foreach {
            case Edit.Write(index, value) => cells(index).set(value)
            case Edit.Attempt(writes, refuse) =>
              UiTxn.attempt {
                writes.foreach((index, value) => cells(index).set(value))
                if refuse then UiTxn.refuse("scoped")
              }
            case Edit.Refuse => UiTxn.refuse("root")
            case Edit.CaughtDefect =>
              try UiTxn.attempt { throw new IllegalArgumentException("defect") }
              catch case _: IllegalArgumentException => ()
          }
        }
        val status = actual match
          case CommitResult.Committed(_) => Result.Accepted
          case CommitResult.Refused(_) => Result.Refused
          case CommitResult.Failed(_) => Result.Failed
          case CommitResult.Joined(_) => throw new IllegalStateException("unexpected nested result")
        runtime.flushEffects(1000)
        agrees &&= status == expected && observations.toVector == next.observations && effects.toVector == next.pendingEffects
        agrees &&= cells.zipWithIndex.forall { (cell, index) =>
          runtime.read(cell.versioned) == Value.Ok(Versioned(next.values(index), Revision(next.revisions(index))))
        }
        reference = next
      }
      runtime.close()
      agrees
    }
  }
