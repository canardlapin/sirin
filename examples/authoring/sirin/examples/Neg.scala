// A-0 authoring corpus: negative cases. Each snippet must FAIL to type-check;
// if one starts compiling, this file stops compiling. Uses only the standard
// library's compile-time testing helpers (no project macros).
package sirin.examples.neg

import scala.annotation.unused
import scala.compiletime.error

import sirin.*

inline def mustNotCompile(inline compiles: Boolean, inline what: String): Unit =
  inline if compiles then error(what) else ()

inline def mustCompile(inline compiles: Boolean, inline what: String): Unit =
  inline if compiles then () else error(what)

object NegativeCases:
  /** Writes need a transaction (VISION §4 rules). */
  def writeOutsideTxn(@unused c: Cell[Int]): Unit =
    mustCompile(scala.compiletime.testing.typeChecks("c"), "Cell fixture did not resolve")
    mustNotCompile(scala.compiletime.testing.typeChecks("c.set(1)"), "a Cell write compiled without a UiTxn")

  /** A `Work` literal cannot be written where a transaction is in scope. */
  def workCannotCaptureTxn(@unused c: Cell[String])(using @unused txn: UiTxn): Unit =
    mustCompile(scala.compiletime.testing.typeChecks("c.get"), "transaction fixture did not resolve")
    mustNotCompile(scala.compiletime.testing.typeChecks("Work(c.get)"), "a Work literal compiled with a UiTxn in scope")

  /** Arrays have no lawful default equality (VISION §5.1). */
  def noArrayCells(@unused scope: Scope): Unit =
    mustCompile(scala.compiletime.testing.typeChecks("scope.cell(1)"), "lawful Cell fixture failed")
    mustNotCompile(scala.compiletime.testing.typeChecks("scope.cell(Array(1, 2))"), "a Cell[Array[Int]] compiled")

  /** Committed is read-only, and cannot read undeclared reactive dependencies. */
  def committedCannotWrite(@unused c: Cell[Int])(using @unused committed: Committed): Unit =
    mustCompile(scala.compiletime.testing.typeChecks("(c, summon[Committed])"), "Committed fixture did not resolve")
    mustNotCompile(scala.compiletime.testing.typeChecks("c.set(2)"), "Committed permitted a write")
    mustNotCompile(scala.compiletime.testing.typeChecks("c.get"), "Committed permitted an undeclared read")

  def latestPolicyIsNarrow(@unused scope: Scope, @unused input: Signal[Int]): Unit =
    mustCompile(scala.compiletime.testing.typeChecks(
      "scope.latest(input, LatestPolicy.LatestOnly)(n => Work(n))"), "valid latest policy failed")
    mustNotCompile(scala.compiletime.testing.typeChecks(
      "scope.latest(input, Acceptance.EveryInvocation)(n => Work(n))"), "latest accepted EveryInvocation")

  def presentationIsNotSignal(@unused presentation: Presentation[Double]): Unit =
    mustCompile(scala.compiletime.testing.typeChecks("presentation.map(_ + 1)"), "presentation fixture failed")
    mustNotCompile(scala.compiletime.testing.typeChecks(
      "val signal: Signal[Double] = presentation"), "Presentation became a Signal")

  /** Positive refinement check alongside the negative capability checks. */
  def modifierPreservesControl(c: Cell[String], ref: NodeRef): TextFieldView =
    TextField.bind(c).padding(4.lp).ref(ref).onCommit(c.set("committed"))

  final class Unrebuildable extends View
  def cannotPreserveUnsupportedSubtype(@unused custom: Unrebuildable): Unit =
    mustCompile(scala.compiletime.testing.typeChecks("custom"), "custom view fixture failed")
    mustNotCompile(scala.compiletime.testing.typeChecks(
      "val unchangedType: Unrebuildable = custom.padding(1.lp)"), "unsupported subtype was preserved")
