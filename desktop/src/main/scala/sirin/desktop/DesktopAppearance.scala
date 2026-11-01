package sirin.desktop

import sirin.*

private[desktop] object DesktopAppearance:
  /** SDL's unknown preference preserves the previous dark development default. */
  def snapshot(nativeTheme: Int, reducedMotion: Boolean): ThemeSnapshot =
    val scheme = nativeTheme match
      case 0 | 2 => ColorScheme.Dark
      case 1 => ColorScheme.Light
      case _ => throw new IllegalArgumentException("unknown native theme value")
    ThemeSnapshot(scheme, reducedMotion)
