package sirin.engine

import sirin.*
import scala.collection.mutable

/** One transition segment as a cubic Hermite curve over clock nanoseconds. A retarget starts a new segment from the
  * current value and velocity, so motion never snaps or queues (VISION §8.2).
  */
private[engine] final class MotionCurve(initial: Double, transition: Transition):
  require(initial.isFinite, "animation value must be finite")
  private val duration = math.max(1L, transition.duration.toNanos).toDouble
  private var from = initial
  private var to = initial
  private var start = 0L
  private var startSlope = 0.0
  private var endSlope = 0.0
  private var settled = true

  def target: Double = to
  def end: Long =
    if settled then
      Long.MinValue
    else
      start + duration.toLong
  def moving(at: Long): Boolean = !settled && at < end

  def value(at: Long): Double =
    if settled || at >= end then
      to
    else
      val s = progress(at)
      val s2 = s * s
      val s3 = s2 * s
      (2 * s3 - 3 * s2 + 1) * from + (s3 - 2 * s2 + s) * startSlope + (-2 * s3 + 3 * s2) * to + (s3 - s2) * endSlope

  /** Value units per nanosecond. */
  def velocity(at: Long): Double =
    if settled || at >= end then
      0.0
    else
      val s = progress(at)
      val s2 = s * s
      val slope = (6 * s2 - 6 * s) * from + (3 * s2 - 4 * s + 1) * startSlope + (-6 * s2 + 6 * s) * to +
        (3 * s2 - 2 * s) * endSlope
      slope / duration

  def retarget(next: Double, at: Long): Unit =
    require(next.isFinite, "animation target must be finite")
    if next != to then
      val current = value(at)
      val travelling = moving(at)
      val speed = velocity(at)
      val (leaving, arriving) = MotionCurve.slopes(transition.easing)
      from = current
      to = next
      start = at
      startSlope =
        if travelling then
          speed * duration
        else
          leaving * (next - current)
      endSlope = arriving * (next - current)
      settled = current == next && startSlope == 0.0

  def settle(next: Double): Unit =
    require(next.isFinite, "animation target must be finite")
    from = next
    to = next
    startSlope = 0.0
    endSlope = 0.0
    settled = true

  private def progress(at: Long): Double = math.max(0.0, math.min(1.0, (at - start).toDouble / duration))

private[engine] object MotionCurve:
  /** Normalized leaving and arriving slopes for a segment that starts at rest. */
  def slopes(easing: Easing): (Double, Double) =
    easing match
      case Easing.Standard =>
        (0.0, 0.0)
      case Easing.Decelerate =>
        (2.0, 0.0)
      case Easing.Linear =>
        (1.0, 1.0)

/** Scope-owned animations. Ticks are samples of each curve at the presented frame's time: they write no graph state
  * and run no transaction. A retarget arrives through a committed watch. Content that the latest frame did not present
  * settles at its target instead of animating, so hidden and unrealized content schedules no frames.
  */
private[engine] final class MotionRuntime(val runtime: HeadlessRuntime, frameNanos: Long):
  require(frameNanos > 0, "positive animation frame interval required")
  private final class Motion(val owner: HeadlessRuntime#OwnedScope, val curve: MotionCurve) extends Presentation[Double]:
    var presented = Long.MinValue
    def alive: Boolean = runtime.scopeAvailable(owner.asInstanceOf[runtime.OwnedScope])
    override private[sirin] def current: Double = sample(this)

  private val motions = mutable.ArrayBuffer.empty[Motion]
  private var frame = 0L
  private var frameTime = 0L
  private var presenting = false
  private var reduced = false

  def create(owner: HeadlessRuntime#OwnedScope, initial: Double, transition: Transition): Presentation[Double] =
    val motion = new Motion(owner, new MotionCurve(initial, transition))
    motions += motion
    runtime.recordUndo(() => motions -= motion)
    motion

  def retarget(presentation: Presentation[Double], target: Double): Unit =
    val motion = presentation.asInstanceOf[Motion]
    if reduced || motion.presented != frame then
      motion.curve.settle(target)
    else
      motion.curve.retarget(target, runtime.timeNanos())

  def reducedMotion: Boolean = reduced

  /** Turning reduced motion on sends every animation to its target; nothing animates afterwards. */
  def reducedMotion_=(value: Boolean): Unit =
    reduced = value
    if value then
      motions.foreach(m => m.curve.settle(m.curve.target))

  def present[A](body: => A): A =
    require(!presenting, "presentation frames do not nest")
    motions.filterInPlace(_.alive)
    frame = Math.addExact(frame, 1L)
    frameTime = runtime.timeNanos()
    presenting = true
    try body
    finally presenting = false

  /** Milliseconds until the next animation frame, or -1 when no presented animation is still in motion. */
  def waitMillis: Int =
    val due = motions.exists(m => m.alive && m.presented == frame && m.curve.end > frameTime)
    if !due then
      -1
    else
      val delta = math.max(0L, frameTime + frameNanos - runtime.timeNanos())
      val ceiling =
        delta / 1000000L + (
          if delta % 1000000L == 0 then
            0L
          else
            1L
        )
      ceiling.min(Int.MaxValue.toLong).toInt

  def count: Int = motions.size

  def active: Int = motions.count(m => m.alive && m.presented == frame && m.curve.end > frameTime)

  private def sample(motion: Motion): Double =
    if presenting then
      if motion.presented != frame && motion.presented != frame - 1 then
        motion.curve.settle(motion.curve.target)
      motion.presented = frame
    motion.curve.value(frameTime)
