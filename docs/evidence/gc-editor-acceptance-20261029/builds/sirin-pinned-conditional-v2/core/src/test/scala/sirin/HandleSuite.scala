package sirin

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.Gen

class HandleSuite extends ScalaCheckSuite:
  private val nonNeg = Gen.chooseNum(0, Int.MaxValue)

  property("slot and generation round-trip through the packed bits") {
    forAll(nonNeg, Gen.chooseNum(Int.MinValue, Int.MaxValue)) { (slot, gen) =>
      val h = Handle.fromBits(Handle(slot, gen).bits)
      h.slot == slot && h.generation == gen
    }
  }

  test("handles with different generations for the same slot differ") {
    assertNotEquals(Handle(7, 1).bits, Handle(7, 2).bits)
  }
