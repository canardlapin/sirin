package sirin.testkit

import sirin.*
import sirin.engine.*
import VirtualCollectionModel.*
import org.scalacheck.{Gen, Prop}
import scala.collection.mutable

class VirtualCollectionAgreementSuite extends munit.ScalaCheckSuite:
  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(200)
  private val edit = Gen.frequency(
    5 -> (for o <- Gen.choose(0, 350); h <- Gen.oneOf(0, 10, 25, 40) yield Op.Scroll(o, h)),
    3 -> Gen.choose(0, 35).map(Op.Filter(_)),
    3 -> Gen.pick(20, 0 until 35).map(xs => Op.Assign(xs.toVector)),
    1 -> Gen.const(Op.Assign(Vector.empty)))
  private val program = Gen.listOfN(40, for a <- edit; b <- edit; refuse <- Gen.frequency(7 -> false, 1 -> true) yield (Vector(a, b), refuse))
  private def read[A](r: HeadlessRuntime, s: Signal[A]): A = r.read(s) match
    case Value.Ok(value) => value
    case Value.Failed(error) => throw error
  private def rows(s: MountedSnapshot): Vector[(Int, Long)] = s match
    case MountedSnapshot.Component(id, p: Int, _) => Vector(p -> id.instance)
    case MountedSnapshot.VirtualRows(_, _, _, _, _, _, _, child) => rows(child)
    case MountedSnapshot.Rows(entries) => entries.flatMap((_, child) => rows(child))
    case _ => Vector.empty
  property("projection revisions, visible intervals, identity and draft lifetimes agree with full-state oracle") {
    Prop.forAll(program) { steps =>
      val r = new HeadlessRuntime
      try
        val owner = r.scope("model")
        val source = owner.keyed(0 until 30)(identity)
        val query = owner.cell(0)
        val filtered = source.filterBy(query)((q, a) => a >= q)
        val drafts = mutable.Map.empty[Long, Cell[Int]]
        val row = Component[Int]("row") { (scope, _) =>
          drafts(scope.asInstanceOf[r.OwnedScope].id) = scope.cell(0)
          View.empty
        }
        val mounted = r.mount(owner, VirtualRows(filtered, Lp(10), 1)(in => row(in)))
        val firstRevision = read(r, filtered.revision)
        var expected = State()
        val identities = mutable.Map.empty[Long, Long]
        for ((edits, refuse), index) <- steps.zipWithIndex do
          val old = rows(read(r, mounted.snapshot)).toMap
          val draft = expected.rows.headOption.map(row => Op.Draft(row.key, index))
          val batch = draft.toVector ++ edits
          val next = VirtualCollectionModel.transact(expected, batch, refuse)
          val result = r.transact {
            batch.foreach {
              case Op.Scroll(o, h) => mounted.setViewport(RowViewport(Lp(o), Lp(h)))
              case Op.Filter(m) => query.set(m)
              case Op.Assign(s) => source.setAll(s)
              case Op.Draft(k, v) => drafts(old(k)).set(v)
            }
            if refuse then UiTxn.refuse("abort")
          }
          assertEquals(result, if refuse then CommitResult.Refused("abort") else CommitResult.Committed(()), s"step=$index $batch")
          val actual = rows(read(r, mounted.snapshot))
          assertEquals(actual.map(_._1), next.rows.map(_.key), s"step=$index $batch")
          actual.zip(next.rows).foreach { case ((_, id), model) =>
            assertEquals(identities.getOrElseUpdate(model.generation, id), id)
            assertEquals(read(r, drafts(id)), model.draft)
          }
          for (_, retired) <- old if !actual.exists(_._2 == retired) do
            assertEquals(r.transact { drafts(retired).set(1) }, CommitResult.Failed(KernelError.DisposedScope))
          assertEquals(read(r, filtered.revision).value - firstRevision.value, next.revision)
          expected = next
        true
      finally r.close()
    }
  }
