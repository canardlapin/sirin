# sirin-text-ui

Pure JVM/Scala Native adapter between core `TextLayout` and Sirin's shared
resolved paragraph data. It depends on `core` and `graphics`; neither
foundational module depends on the other. No native service or UI tree is
required to adapt or query an already resolved result.

```scala
import sirin.*
import sirin.text.ResolvedTextLayout

// resolved is a graphics ParagraphLayout or WrappedParagraphLayout produced
// by the shared pipeline. Native owners may already have been closed.
val adapted = ResolvedTextLayout(resolved)
val layout: TextLayout = adapted
val caret = layout.caret(2, TextAffinity.Downstream)
val hit = layout.hit(sirin.Point(40.lp, 12.lp))
val regions = layout.selection(1, 4)
// Retain hit.primary.position (offset + affinity) and hit.alternatives.
```

Source offsets in core are original **UTF-16 code units**. `TextPosition` names that unit explicitly
and carries logical `Upstream`/`Downstream` affinity. Caret calls require
affinity; there is no single-offset overload that chooses it silently.
Negative/out-of-source, surrogate or grapheme-interior caret queries return
`None` (`caretsAt` returns an empty vector). Invalid selections throw without
snapping. A negative `TextPosition` is invalid at construction.

Caret, hit and selection metadata is preserved: actual line index, zero-width
line-height bounds, cluster/GDEF/interpolation quality, all hit alternatives,
and selection interpolation flags. Nearest-stop tie policy comes from the
shared geometry: downstream then source order, while every collocated or
equidistant stop remains inspectable. Bidi and wrap affinities can yield
different coordinates/lines for the same UTF-16 offset. This is not a visual
arrow-key navigation or strong/weak cursor policy.

`size`, `inkBounds`, glyph/feature `isComplete` and wrapping `hasOverflow` read
the retained paragraph. UTF-8 glyph/source offsets convert through its original
`TextSource`. Logical Double coordinates become core `Lp` at **1:1**, with no
rounding, snapping or display scale. Input points convert back by the same rule;
nonfinite points reject. Hosts apply the same outer drawing transform to input
and selection/caret geometry.

`adapted.resolved` is the identical paragraph object, not a reconstructed model.
`adapted.record(canvas2D, coreOrigin, coreColor)` emits its exact stored glyph
runs and placements. Core ARGB bytes convert to graphics' straight-alpha sRGB
components; no premultiplication or rescaling happens here. The pure
[`graphics-ui`](../graphics-ui/README.md) Canvas bridge now delegates here for
resolved text. GPU drawing, mounted lifetime ownership and phase dispatch remain
host work.

The existing `TextServices.layout(...)(using Committed)` capability remains.
The [isolated native paragraph component](../probes/paragraph/README.md#core-text-services)
now implements it with a supplied style/font map. Finite nonnegative widths use
the shared word-wrapper; positive infinity requests the same pipeline's
unwrapped paragraph. Unknown styles, invalid widths, unsupported input and
budget failures reject explicitly. Core never imports native handles or font
discovery. Native result production remains qualified only in that component,
not a production UI host.

```sh
sbt testJVM testNative  # includes the pure bridge and core API checks
sbt probeParagraph/test  # real fonts/Unicode and core consumers, macOS arm64
```

The [adapter receipt](../docs/evidence/graphics/2026-10-28-text-ui.md) records
local qualification. Editing commands, navigation, IME, accessibility policy,
production phase/worker dispatch, font discovery/epochs and GPU text remain open.
