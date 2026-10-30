package sirin.testkit

import sirin.testkit.ReconciliationModel.*

/** Exact public Linux Native CI40974637787 counterexample; never regenerated. */
object ReconciliationCiRegression:
  val seed = "l8Dl-ZJbwAFzUVsay_UCl1zuWxTHh-oHefe7es6XzbN="
  val steps: Vector[Step] = Vector(
    Step.Batch(Vector(Op.Assign(Vector(Item(0, 0), Item(3, -1), Item(4, -3), Item(2, 3))), Op.Draft(0, 3), Op.Assign(Vector(Item(0, -2), Item(2, 1), Item(3, -3), Item(1, 2))))),
    Step.Batch(Vector(Op.Assign(Vector(Item(2, 3), Item(3, -1), Item(4, 3), Item(0, -2))), Op.Assign(Vector(Item(4, 0), Item(1, 1), Item(2, 3))), Op.Assign(Vector(Item(2, 0), Item(0, -1), Item(3, -1), Item(1, 0))))),
    Step.Batch(Vector(Op.Assign(Vector(Item(1, 0), Item(4, -3), Item(3, -2), Item(2, 0))), Op.Attempt(Vector(Op.Rebuild, Op.CaughtDefect, Op.Assign(Vector(Item(2, -1), Item(3, -1)))), false), Op.Attempt(Vector(Op.Assign(Vector(Item(0, 3), Item(2, -3), Item(4, 0), Item(3, -1))), Op.Assign(Vector(Item(3, 2))), Op.CaughtDefect), true))),
    Step.Deliver(3),
    Step.Batch(Vector(Op.Assign(Vector(Item(3, -1), Item(3, -3), Item(2, -2))), Op.Assign(Vector(Item(3, 3), Item(1, -3), Item(4, 0), Item(2, -1))), Op.Read(1))),
    Step.Batch(Vector(Op.Attempt(Vector(Op.CaughtDefect, Op.Assign(Vector(Item(3, 1), Item(4, 3))), Op.Assign(Vector(Item(2, 2), Item(1, 3), Item(4, 2)))), true), Op.Read(4), Op.Draft(0, -1))),
    Step.Drain(3),
    Step.Batch(Vector(Op.Read(1), Op.Draft(4, 2), Op.Attempt(Vector(Op.CaughtDefect, Op.Refuse, Op.Draft(1, -1)), false))),
    Step.Batch(Vector(Op.Attempt(Vector(Op.Assign(Vector(Item(3, -2))), Op.Rebuild, Op.CaughtDefect), false), Op.Attempt(Vector(Op.Read(0), Op.Refuse, Op.Assign(Vector(Item(4, 0), Item(0, 0), Item(1, 3), Item(3, 1)))), false), Op.Attempt(Vector(Op.Read(0), Op.Assign(Vector(Item(1, -2), Item(0, 3), Item(4, 3))), Op.Assign(Vector(Item(0, -2), Item(3, -3), Item(1, -3)))), true))),
    Step.Batch(Vector(Op.Assign(Vector(Item(4, -2), Item(1, 2), Item(0, 0), Item(3, -1))), Op.Draft(0, -3), Op.Attempt(Vector(Op.Read(2), Op.Assign(Vector(Item(1, -2), Item(4, 2), Item(3, -3))), Op.Draft(1, -2)), false))),
    Step.Batch(Vector(Op.Read(4), Op.Assign(Vector(Item(0, 3), Item(3, 1), Item(2, 3))), Op.Assign(Vector(Item(1, 0), Item(2, 0), Item(2, -1))))),
    Step.Batch(Vector(Op.Assign(Vector(Item(1, -3), Item(3, 2), Item(0, 1))), Op.Attempt(Vector(Op.Assign(Vector(Item(2, 3), Item(1, 2), Item(4, 1))), Op.Assign(Vector(Item(0, -2), Item(1, -2))), Op.CaughtDefect), false), Op.Assign(Vector(Item(1, -1), Item(2, -2), Item(4, 0))))),
    Step.Batch(Vector(Op.Assign(Vector(Item(1, -1), Item(3, 2), Item(2, 3))), Op.Attempt(Vector(Op.Draft(1, 0), Op.Refuse, Op.Assign(Vector(Item(2, -2), Item(3, -1), Item(0, -1)))), false), Op.Attempt(Vector(Op.Assign(Vector(Item(0, 2), Item(4, 3), Item(1, 0))), Op.Rebuild, Op.Rebuild), false))),
    Step.Batch(Vector(Op.Assign(Vector(Item(0, -3), Item(4, 0), Item(1, -3))), Op.Draft(3, -3), Op.Assign(Vector(Item(0, -1), Item(2, 2))))),
    Step.Batch(Vector(Op.Assign(Vector(Item(2, 0), Item(1, -2), Item(4, -1), Item(0, -1))), Op.Draft(0, 3), Op.Attempt(Vector(Op.Draft(0, 1), Op.Assign(Vector(Item(0, -1), Item(1, 1))), Op.Refuse), true))),
    Step.Batch(Vector(Op.Assign(Vector(Item(3, 2), Item(0, -3), Item(4, -3))), Op.CaughtDefect, Op.Attempt(Vector(Op.Assign(Vector(Item(3, 0), Item(0, 3), Item(4, 1))), Op.Draft(4, 0), Op.Assign(Vector(Item(2, -1), Item(0, 0), Item(1, 3), Item(4, 3)))), false))),
    Step.Batch(Vector(Op.Read(2), Op.Rebuild, Op.Assign(Vector(Item(2, -1), Item(3, 0), Item(0, -1), Item(1, -2))))),
    Step.Batch(Vector(Op.Draft(4, 0), Op.Assign(Vector(Item(2, 1), Item(1, -1), Item(3, 3))), Op.Rebuild)),
    Step.Batch(Vector(Op.Assign(Vector(Item(4, 0), Item(3, 0), Item(1, -1), Item(2, 0))), Op.Draft(0, -1), Op.Attempt(Vector(Op.Read(2), Op.Read(0), Op.Rebuild), true))),
    Step.Batch(Vector(Op.Draft(4, -2), Op.Attempt(Vector(Op.Read(4), Op.Assign(Vector(Item(0, 0), Item(1, 1), Item(3, -3))), Op.Rebuild), true), Op.Assign(Vector(Item(1, -1), Item(0, -3), Item(3, 2), Item(2, 1))))),
    Step.Batch(Vector(Op.Rebuild, Op.Attempt(Vector(Op.Draft(1, 2), Op.Rebuild, Op.Read(4)), true), Op.Attempt(Vector(Op.Refuse, Op.Assign(Vector(Item(1, -3), Item(0, 0), Item(3, 1), Item(4, 1))), Op.Assign(Vector(Item(4, 1), Item(0, 2), Item(3, 0)))), false))),
    Step.Batch(Vector(Op.Assign(Vector(Item(1, 0), Item(0, 0), Item(2, 3))), Op.Attempt(Vector(Op.Assign(Vector(Item(3, 0), Item(1, -1), Item(4, -3), Item(0, -3))), Op.Read(1), Op.Rebuild), true), Op.Rebuild)),
    Step.Deliver(36),
    Step.Deliver(40),
    Step.Drain(3),
    Step.Batch(Vector(Op.Read(3), Op.Assign(Vector(Item(0, -1), Item(1, 1), Item(2, 0))), Op.CaughtDefect)),
    Step.Drain(2),
    Step.Deliver(2),
    Step.Batch(Vector(Op.Assign(Vector(Item(2, 3), Item(0, 0), Item(1, 3))), Op.Assign(Vector(Item(1, -2), Item(0, -2), Item(4, -1), Item(2, 0))), Op.Assign(Vector(Item(1, -3), Item(4, -2), Item(3, -2), Item(2, -2))))),
    Step.Batch(Vector(Op.Refuse, Op.Assign(Vector(Item(3, -2), Item(4, 0), Item(2, 1), Item(1, 2), Item(0, 3))), Op.Rebuild)),
    Step.Batch(Vector(Op.Assign(Vector(Item(0, 0), Item(4, -1), Item(3, 1), Item(2, 3))), Op.Assign(Vector(Item(2, 1), Item(3, 0), Item(4, 1), Item(0, 2))), Op.Refuse)),
    Step.Batch(Vector(Op.Assign(Vector(Item(3, -1), Item(4, 2), Item(2, 3))), Op.Attempt(Vector(Op.Assign(Vector(Item(0, 2), Item(3, 2), Item(4, -3))), Op.Assign(Vector(Item(0, -2))), Op.Assign(Vector(Item(1, -1), Item(0, 2), Item(0, -1)))), true), Op.Read(3))),
    Step.Batch(Vector(Op.Assign(Vector(Item(3, 1), Item(1, 2), Item(3, -1))), Op.Assign(Vector(Item(4, 3), Item(0, 3), Item(3, -2), Item(1, 1))), Op.Rebuild)),
    Step.Batch(Vector(Op.Attempt(Vector(Op.Read(4), Op.Read(1), Op.Assign(Vector(Item(3, -2), Item(0, -2), Item(4, 0)))), true), Op.Rebuild, Op.Draft(4, -3))),
    Step.Batch(Vector(Op.Rebuild, Op.Rebuild, Op.Attempt(Vector(Op.Rebuild, Op.Read(4), Op.Assign(Vector(Item(3, -3), Item(4, 2)))), true))),
    Step.Batch(Vector(Op.Assign(Vector(Item(4, 0), Item(0, -2), Item(2, -1))), Op.Read(0), Op.Assign(Vector(Item(1, 3), Item(4, 3), Item(0, 3), Item(3, -2))))),
    Step.Drain(1),
    Step.Drain(1),
    Step.Batch(Vector(Op.Assign(Vector(Item(3, -2), Item(0, -2), Item(4, -3))), Op.Attempt(Vector(Op.Refuse, Op.Rebuild, Op.Assign(Vector(Item(2, -1), Item(3, 1), Item(4, -3), Item(1, -3)))), false), Op.Assign(Vector(Item(1, 3), Item(0, 1), Item(4, 2), Item(3, 0))))),
    Step.Batch(Vector(Op.Rebuild, Op.Assign(Vector(Item(2, -1), Item(3, -1))), Op.Read(2))))

/** Preserve the original randomized suite and independently replay its failing seed. */
class ReconciliationLinuxSeedSuite extends ReconciliationAgreementSuite:
  override def scalaCheckInitialSeed: String = ReconciliationCiRegression.seed
