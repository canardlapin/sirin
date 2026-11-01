package sirin

enum ColorScheme:
  case Light, Dark

/** Immutable default palette, safe to retain in a worker frame. */
final case class ThemeSnapshot(scheme: ColorScheme = ColorScheme.Dark, reducedMotion: Boolean = false) extends Theme:
  def color(role: ColorRole): Color = (scheme, role) match
    case (ColorScheme.Dark, ColorRole.Surface) => Color.rgba(20, 25, 35)
    case (ColorScheme.Dark, ColorRole.SurfaceRaised) => Color.rgba(32, 39, 53)
    case (ColorScheme.Dark, ColorRole.OnSurface) => Color.rgba(230, 233, 239)
    case (ColorScheme.Dark, ColorRole.Muted) => Color.rgba(158, 170, 190)
    case (ColorScheme.Dark, ColorRole.Accent | ColorRole.FocusRing) => Color.rgba(115, 185, 248)
    case (ColorScheme.Dark, ColorRole.OnAccent) => Color.rgba(20, 25, 35)
    case (ColorScheme.Dark, ColorRole.Danger) => Color.rgba(255, 145, 145)
    case (ColorScheme.Dark, ColorRole.Selection) => Color.rgba(40, 76, 120)
    case (ColorScheme.Dark, ColorRole.Border) => Color.rgba(123, 139, 162)
    case (ColorScheme.Light, ColorRole.Surface) => Color.rgba(245, 247, 250)
    case (ColorScheme.Light, ColorRole.SurfaceRaised) => Color.rgba(255, 255, 255)
    case (ColorScheme.Light, ColorRole.OnSurface) => Color.rgba(25, 29, 35)
    case (ColorScheme.Light, ColorRole.Muted) => Color.rgba(75, 80, 99)
    case (ColorScheme.Light, ColorRole.Accent | ColorRole.FocusRing) => Color.rgba(24, 83, 148)
    case (ColorScheme.Light, ColorRole.OnAccent) => Color.rgba(255, 255, 255)
    case (ColorScheme.Light, ColorRole.Danger) => Color.rgba(164, 25, 25)
    case (ColorScheme.Light, ColorRole.Selection) => Color.rgba(201, 224, 250)
    case (ColorScheme.Light, ColorRole.Border) => Color.rgba(87, 98, 108)
