package sirin.units

/** Logical pixels. Device pixels (`Px`) belong to the backend-author API. */
opaque type Lp = Double

object Lp:
  val zero: Lp = 0.0
  def apply(value: Double): Lp = value

  extension (a: Lp)
    def toDouble: Double = a
    def +(b: Lp): Lp = a + b
    def -(b: Lp): Lp = a - b
    def *(k: Double): Lp = a * k
    def /(k: Double): Lp = a / k
    def max(b: Lp): Lp = math.max(a, b)
    def min(b: Lp): Lp = math.min(a, b)
    def compare(b: Lp): Int = java.lang.Double.compare(a, b)

object syntax:
  extension (n: Int)
    def lp: Lp = Lp(n.toDouble)
  extension (n: Double)
    def lp: Lp = Lp(n)
