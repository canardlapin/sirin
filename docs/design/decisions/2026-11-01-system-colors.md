# System light and dark colors

The persistent macOS workbench follows the system light/dark preference. The
owner reads SDL's system theme at startup and after each native event; SDL's
system-theme event wakes the existing event loop. Unknown maps to the existing
dark default. No timer polls the preference, and the application does not change
the operating system setting.

`ThemeSnapshot` is an immutable, public semantic palette with light and dark
variants. Every frame payload captures its palette and Reduce Motion value.
Workers use that captured value, never a mutable process-wide color preference.
A changed palette participates in frame equality, so an older rendered result
cannot replace a newer requested appearance. The host advances its existing
theme-color phase epoch when the palette changes. Elements declaring a layout
dependency on theme colors remeasure; ordinary controls retain their text and
measurement cache entries. This changes no transaction, identity or work policy.

Standard controls use semantic roles for backgrounds, borders, validation,
selection, focus and primary-button text. Accent backgrounds use OnAccent text.
Press feedback uses contrasting semantic ink with bounded alpha. Fixed dark RGB values are not
part of those controls' contract. Applications can still provide their own Theme.
Focus borders inside an accent-filled button or checked box use OnAccent, so the
border remains visible against its own background in either palette.
The checkbox uses a square focus border. Its former rounded focus stroke exceeded
the reference renderer's unchanged per-sample work limit in the expanded form
regression; the square border retains a visible indicator in both render paths.
The custom diagram and NoteField specimens follow the same semantic palette:
diagram gradients interpolate the two surface roles, and text selections,
composition marks and carets use selection/accent roles. Keeping their fixed
dark fills or pale caret ink would undermine the host's light appearance.

The new native query takes an application pointer and returns a scalar enum. It
rejects missing, retired and wrong-thread owners before calling SDL. JVM FFM and
a Scala Native extern reach the same C declaration. The query is non-blocking;
no managed callback or by-value struct crosses the boundary.

Rendering checks must switch dark to light and back through one render session,
compare cached output with a fresh render, and retain draft/focus state. Palette
checks measure text and focus/border contrast. Those checks do not establish
actual system switching, VoiceOver, display-scale or owner visual acceptance.
The existing launch candidates remain intact until their requested tests run.

Alternatives set aside:

- A fixed dark skin leaves the light-theme requirement unmet; a preference
  polling timer would add unnecessary idle work when SDL already sends events.
- A mutable worker-visible theme could mix palettes within an in-flight frame.
  Immutable payload values make appearance and stale-frame rejection agree.
- Clearing every measurement and shaping cache would discard work unrelated to
  colors. Existing declared global dependencies provide the narrower invalidation.
- Accent-colored focus and press feedback disappears on accent-filled controls.
  Contrasting semantic ink keeps those states visible. Retaining the checkbox's
  rounded focus stroke would retain a demonstrated reference-renderer refusal;
  the square indicator avoids changing renderer budgets.
