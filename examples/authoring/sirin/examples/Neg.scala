// A-0 authoring corpus: negative cases. Each snippet must FAIL to type-check;
// if one starts compiling, this file stops compiling. Uses only the standard
// library's compile-time testing helpers (no project macros).
package sirin.examples.neg

import scala.annotation.unused
import scala.compiletime.error

import sirin.*

inline def mustNotCompile(inline compiles: Boolean, inline what: String): Unit =
  inline if compiles then error(what) else ()

object NegativeCases:
  /** Writes need a transaction (VISION §4 rules). */
  def writeOutsideTxn(@unused c: Cell[Int]): Unit =
    mustNotCompile(scala.compiletime.testing.typeChecks("c.set(1)"), "a Cell write compiled without a UiTxn")

  /** A `Work` literal cannot be written where a transaction is in scope. */
  def workCannotCaptureTxn(@unused c: Cell[String])(using @unused txn: UiTxn): Unit =
    mustNotCompile(scala.compiletime.testing.typeChecks("Work(c.get)"), "a Work literal compiled with a UiTxn in scope")

  /** Arrays have no lawful default equality (VISION §5.1). */
  def noArrayCells(@unused scope: Scope): Unit =
    mustNotCompile(scala.compiletime.testing.typeChecks("scope.cell(Array(1, 2))"), "a Cell[Array[Int]] compiled")
