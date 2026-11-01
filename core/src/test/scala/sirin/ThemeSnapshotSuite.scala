package sirin

class ThemeSnapshotSuite extends munit.FunSuite:
  private def luminance(color: Color): Double =
    def linear(value: Int): Double =
      val channel = value / 255.0
      if channel <= 0.04045 then channel / 12.92 else math.pow((channel + 0.055) / 1.055, 2.4)
    val value = color.argb
    linear((value >>> 16) & 255) * 0.2126 + linear((value >>> 8) & 255) * 0.7152 +
      linear(value & 255) * 0.0722
  private def contrast(a: Color, b: Color): Double =
    val x = luminance(a)
    val y = luminance(b)
    (math.max(x, y) + 0.05) / (math.min(x, y) + 0.05)

  for scheme <- ColorScheme.values do
    test(s"$scheme text, selection, primary controls and focus meet the palette contrast bounds") {
      val theme = ThemeSnapshot(scheme)
      for background <- Vector(ColorRole.Surface, ColorRole.SurfaceRaised) do
        for foreground <- Vector(ColorRole.OnSurface, ColorRole.Muted, ColorRole.Danger) do
          assert(contrast(theme.color(foreground), theme.color(background)) >= 4.5,
            s"$foreground on $background")
        for foreground <- Vector(ColorRole.Border, ColorRole.FocusRing) do
          assert(contrast(theme.color(foreground), theme.color(background)) >= 3.0,
            s"$foreground against $background")
      assert(contrast(theme.color(ColorRole.OnAccent), theme.color(ColorRole.Accent)) >= 4.5)
      assert(contrast(theme.color(ColorRole.OnSurface), theme.color(ColorRole.Selection)) >= 4.5)
      assert(ColorRole.values.forall(role => (theme.color(role).argb >>> 24) == 255))
      assertEquals(theme.copy(reducedMotion = true).color(ColorRole.Surface), theme.color(ColorRole.Surface))
    }

  test("press alpha preserves the role color and rejects out-of-range alpha") {
    val accent = ThemeSnapshot(ColorScheme.Light).color(ColorRole.Accent)
    assertEquals(accent.withAlpha(46).argb & 0x00ffffff, accent.argb & 0x00ffffff)
    assertEquals(accent.withAlpha(46).argb >>> 24, 46)
    intercept[IllegalArgumentException](accent.withAlpha(-1))
    intercept[IllegalArgumentException](accent.withAlpha(256))
  }
