// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
package sirin

import scala.concurrent.duration.*

enum TextStyle:
  case Title,
    Heading,
    Body,
    Label,
    Caption,
    Mono

/** Semantic color roles (VISION §8). Concrete values come from the theme. */
enum ColorRole:
  case Surface,
    SurfaceRaised,
    OnSurface,
    Muted,
    Accent,
    OnAccent,
    Danger,
    Selection,
    FocusRing,
    Border

trait Theme:
  def color(role: ColorRole): Color
  def reducedMotion: Boolean

object Spacing:
  val xsmall: Lp = 4.lp
  val small: Lp = 8.lp
  val medium: Lp = 12.lp
  val large: Lp = 20.lp

enum Easing:
  case Standard,
    Decelerate,
    Linear

/** One of a few well-defined transitions (VISION §8.2). */
final case class Transition(duration: FiniteDuration, easing: Easing)

object Transition:
  val standard: Transition = Transition(160.millis, Easing.Standard)
  val quick: Transition = Transition(90.millis, Easing.Decelerate)

/** Presentation is deliberately not a Signal: ticks create no graph writes. */
trait Presentation[+A]
object Presentation:
  def const[A](value: A): Presentation[A] = ???

  /** Projection stays in the presentation phase, never signal composition. */
  extension [A](value: Presentation[A])
    def map[B](f: A => B): Presentation[B] = ???

/** Explicit layout animation channel; invalidation cost is charged to layout. */
trait LayoutPresentation[+A]
object LayoutPresentation:
  def from[A](value: Presentation[A]): LayoutPresentation[A] = ???

/** Declared global invalidation sources, recorded with phase-specific cause IDs. */
enum PhaseDependency:
  case ThemeColors,
    FontMetrics,
    DisplayScale
