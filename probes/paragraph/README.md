# Paragraph integration component

`probeParagraph` joins ICU4X analysis with real HarfBuzz/FreeType shaping through
public standalone graphics data. It implements script itemization, explicit
supplied-face fallback and shared unwrapped/wrapped paragraph placement. It is isolated from
the UI host and contains one ICU4X Rust static library plus C/C++ font libraries,
without wgpu or AccessKit. Production host integration still needs `sirin-native`.

```sh
native/fetch-macos.sh --text
native/fetch-macos.sh --unicode
sbt probeParagraph/test
```

This component is not root-aggregated and has no successful placeholder backend.
Both native recipes and the integration link are currently qualified on macOS
arm64 only. Font and Unicode fixture hashes are verified before tests. CI's
macOS font job also fetches Unicode and runs the paragraph tests; local execution
does not establish that remote CI has run.

```scala
import sirin.graphics.*
import sirin.graphics.text.{NativeFont, NativeParagraph}
import sirin.graphics.unicode.NativeUnicode

// Caller supplies exact immutable static-font snapshots in fallback order.
val faces = fontSnapshots.map(data => NativeFont.open(data, 24))
val unicode = NativeUnicode.open()
val paragraph = try
  NativeParagraph.wrap("office κόσμος سلام דָבר क्षि", faces, unicode, maxWidth = 240)
finally
  faces.foreach(_.close())
  unicode.close()

// No native font or Unicode service is needed to inspect or record this result.
val measured = paragraph.advanceBounds
val caret = paragraph.geometry.caretUtf16(Utf16Offset(2), CaretAffinity.Downstream)
val hit = paragraph.geometry.hit(Point(40, 12)) // paragraph-local input
val selection = paragraph.geometry.selectionUtf16(Utf16Offset(1), Utf16Offset(4))
val drawing = Drawing.record { canvas =>
  paragraph.record(canvas, Point(12, 8), Paint.Solid(Color(0, 0, 0)))
}
// Inspect paragraph.isComplete and paragraph.issues for missing/unsupported text.
// Use hit.primary.position and retain hit.alternatives when choosing editing policy.
```

## Itemization and shaping

`Script` accepts canonical four-letter tags, rather than four fixed scripts.
ICU4X supplies actual Script/Script_Extensions, paired-bracket and ignorable
properties from the pinned Unicode 18 data. The allocation-free scalar property
ABI returns at most 64 script-extension tags; excess is an explicit failure.

Scala's deterministic itemization policy keeps each grapheme intact. Its first
explicit base script wins over inherited marks. Otherwise compatible surrounding
scripts and Script_Extensions resolve Common/Inherited values, with preceding
context preferred, then following context, then tag order. Paired brackets use
the opener's script. This is a documented shaping policy using real properties,
not a claim that Unicode prescribes one unique itemization. General quotation
pairing and script/language tailoring are not qualified. Unknown script values
remain explicit. A visible bidi-level change inside a grapheme rejects rather
than silently splitting the cluster.

Logical items are split only at grapheme boundaries when script or resolved
level changes. HarfBuzz receives the complete containing line plus the logical
item's byte range, script, level-derived direction and caller language. For
unwrapped layout the line is the whole paragraph. `NativeFont.shapeRange` with
three arguments retains full-source context; its four-argument overload admits
an explicit codepoint-aligned context window containing the item. It sets
context-edge BOT/EOT flags, retains source-global
UTF-8 clusters and reports run-local glyph positions. Source is never reversed
or reconstructed from the bidi codepoint map. HarfBuzz supplies contextual forms,
combining placement and direction-dependent mirroring; language is explicit
(`und` by default), without automatic language detection.

## Explicit fallback policy

The first supplied face that shapes an entire script/level item without glyph
zero wins. This deliberately favors one coherent face over inserting font
boundaries wherever an earlier face happens to cover individual characters.
If no supplied face covers the item, fallback atoms are whole graphemes enlarged
to avoid splitting any shaping cluster from the full-item attempts. The first
face that shapes the complete atom without glyph zero wins. Adjacent choices
of the same face are merged and the final logical range is reshaped with full
context; glyph arrays from attempts are never spliced into a final run.

If no face covers an atom, the first face supplies its real notdef output. Final
glyph-zero cluster ranges become `MissingGlyphs` issues and `isComplete` is false.
The glyph IDs, source and affected ranges remain inspectable; no invented font,
replacement text or fixed-width estimator hides a failure. Unsupported Unicode
variation selectors are explicitly reported even when HarfBuzz silently ignores
them. Static-font UVS/variation-sequence and color/variable-font support remain
unqualified. Fonts are exact caller-owned bytes/index/size, without OS discovery,
font-install epochs or mutable family-name rebinding.

## Word wrapping

`NativeParagraph.wrap` chooses from the pinned ICU4X line opportunities that are
also grapheme boundaries. It shapes every candidate line from the current start
to a legal end and selects the furthest end whose actual advance fits `maxWidth`.
Candidate widths are not assumed monotonic under contextual shaping/kerning. If
no candidate fits, the first legal span is retained and overflow is reported.
The prototype deliberately favors exact bounded evaluation over a glyph-width
estimate; production performance and candidate reuse remain unqualified.

Paragraph bidi and script/bracket context are resolved from the complete source.
Each candidate reapplies L1/L2 for its line, then shapes and resolves fonts within
that line's context window. Arabic cannot join through the wrap; ligatures are
reshaped instead of sliced. Logical input and global UTF-8 clusters remain intact.
Only the selected line's issues and glyphs enter the result; work budgets include
all discarded candidates too.

The policy retains all source spaces and codepoints: no collapse, trimming,
justification, emergency grapheme breaks, hyphen insertion or right-alignment.
An unbreakable word/NBSP span may exceed `maxWidth` (which accepts finite zero).
`hasOverflow` describes that independently of `isComplete` glyph/feature coverage.
Actual bounds are not clamped to the requested width. Soft hyphens reject until
a discretionary hyphenation policy exists; tabs/hard separators still reject.

## Shared result and ownership

`TextLineLayout` retains source/Unicode analysis, line range/bidi results, script items,
the ordered requested faces, logical resolved runs, visual run placements,
issues, advance bounds and ink bounds. Logical runs and items partition source;
all glyph clusters map into the original text. Each run retains its exact font.
Upstream L2 orders the resolved **runs**; HarfBuzz orders glyphs within them.
Combining marks and joiners remain in logical shaping context.

`ParagraphLayout.line` exposes this representation for one unwrapped paragraph.
`WrappedParagraphLayout.lines` contains the same line data plus `PlacedTextLine`
origins. Line ranges partition the complete source, including retained whitespace.
Coordinates are y-down from the paragraph's top-left. A line's width is the sum of visual
run advances, without trailing-space trimming, justification or right-alignment.
All runs in one line share a baseline derived from the maximum actual ascent/descent of used
faces, with nonnegative maximum line gap split above/below. Empty text uses the
first supplied face's real metrics and has zero width/no glyphs. Ink is separate
from advance bounds and can overhang them. Wrapped height is the sum of line
heights and width is the maximum actual line width; each line starts at x=0 and
its own local baseline. `PlacedTextLine.baseline` includes the line's vertical
origin. Negative or vertical run advances
reject explicitly in this horizontal component.

Measurement reads stored metrics. `record` emits those same runs/positions through
`Canvas2D.glyphs` without a second shape or fallback pass. Drawing resource
inspection includes used fonts; the layout itself additionally retains every
requested face that influenced resolution. The same result's lazy `geometry`
adds caret/hit/selection queries without another shaping or fallback pass.
The pure [`text-ui`](../../text-ui/README.md) adapter now exposes the same result
through core `TextLayout`, preserving UTF-16 affinity, hit alternatives and
selection metadata. Production UI host integration remains open.

## Core text services

`NativeTextServices` implements core `TextServices` in this isolated component.
A caller supplies an immutable map of `TextStyle` to `NativeTextStyle` with exact
already-open font faces and paragraph options, plus its Unicode service and
wrapping limits. No face discovery, default styles, implicit size adjustments,
resource ownership or worker thread is introduced. Unconfigured styles reject.

`layout(...)(using Committed)` preserves the public read-only call-site
capability. Finite nonnegative logical widths call `NativeParagraph.wrap`;
positive infinity calls `NativeParagraph.layout`. Negative/NaN widths reject.
The same fallback, shaping, segmentation, budget and unsupported-input rules
therefore apply. It returns a `ResolvedTextLayout` over that exact result.
Each service stays on its creating Scala thread. The caller closes the supplied
fonts/Unicode service; later preparation fails while earlier immutable results
remain queryable and recordable. The adapter itself requires no open service.

This does not implement mounted phase dispatch or asynchronously schedule layout.
Expensive preparation still follows VISION's existing worker/phase rules. Tests
provide the public `Committed` marker at their call sites and exercise actual
native data; they do not certify a UI scheduler, TextField or IME.
See [the adapter receipt](../../docs/evidence/graphics/2026-10-28-text-ui.md).

## Editing geometry

`ParagraphLayout.geometry`, `TextLineLayout.geometry` and
`WrappedParagraphLayout.geometry` expose standalone immutable `TextGeometry`.
Preparation is lazy so discarded wrap candidates need not build editing cells;
it can first run after native closure. It uses original source, pinned Unicode
graphemes, HarfBuzz clusters and stored visual run placements. Intersecting
grapheme and shaping-cluster edges merges clusters that would otherwise split
an extended grapheme. Every editing cell covers exactly one complete grapheme.
Missing glyphs and invisible controls retain their source mappings.

`TextPosition` contains a typed UTF-8 offset and logical `CaretAffinity`.
`Upstream` uses the preceding grapheme's trailing edge; `Downstream` uses the
following grapheme's leading edge. Both affinities use the one available edge
at outer source endpoints. Bidi boundaries may have distinct x positions;
wrapped boundaries may have distinct lines. `caret` returns an optional
zero-width, full-line-height `TextCaret`. `caretsAt` preserves both affinities.
`caretUtf16` converts through the original source's codepoint mapping and then
checks the same grapheme stops; no surrogate/continuation/combining/emoji
interiors become carets. Invalid queries return `None`, without snapping.

Cluster edges use actual shaped pen advances, independently of ink overhang or
mark offsets. For a single-glyph, multi-grapheme atom with one scalar per
grapheme, usable GDEF coordinates are added to the actual shaped glyph origin,
including positioning offsets. Only the exact expected count, strictly ordered
and strictly inside the shaped advance span, is accepted. RTL reverses their
logical association. Otherwise, interiors divide the **actual atom advance**
equally among its graphemes. `CaretEdgeKind` distinguishes `ClusterEdge`,
`FontLigature` and `Interpolated`. This explicit fallback is not a font metric
or a claim of browser/font-specific caret equivalence. It does not alter glyph
positions, line width or shape. GDEF copying is capped at 64 positions per
glyph and retained only when matching scalar count; geometry additionally
requires grapheme correspondence. No font table or foreign handle escapes.

`hit(Point)` clamps to a line's vertical band, choosing the lower line exactly
at a shared boundary, and then the nearest horizontal caret stop. Coordinates
outside the layout clamp to available lines/stops. Ties prefer downstream and
then source order; `TextHit.alternatives` retains all other collocated or
equidistant stops. A single numeric hit offset cannot encode this ambiguity.
This is a nearest-stop policy, not visual arrow-key navigation or bidi strong/
weak cursor policy.

`selection(Utf8Range)` and `selectionUtf16` require ordered grapheme-aligned
endpoints within the queried layout. Invalid ranges throw rather than silently
expand. They union selected cells into sorted `TextSelectionRegion`s per line;
bidi selections can be discontiguous. Regions cover actual advances, including
whitespace, at full line height. Zero-width controls add no filled area. The
`interpolated` flag propagates inferred boundaries. Selection rectangles are
background geometry, not ink clipping or recoloring of a partial ligature.

Line-local geometry uses local coordinates and line index 0. Wrapped geometry
adds the exact `PlacedTextLine` origin and retains paragraph-wide line indices.
Hosts map input/rectangles through the same drawing transform. Standalone
geometry adds no editing commands, navigation, IME state or accessibility nodes.

The caller owns native faces and the Unicode service on their creating Scala
thread. Layout adds no finalizer, callback, worker or kernel lifetime. Immutable
results retain copied data and outlive native closure. Slow preparation belongs
off the UI owner under the existing scheduler policy. Shaping copies full source
context into a native zone for each attempt; results copy back before retirement.
The existing shape extern and allocating `su_order` are `@blocking`, inventoried
in VISION §5.4; bounded script-property reads are plain externs.

Default budgets: 64 KiB source UTF-8 (with an early code-unit guard), 16 distinct
supplied faces, 4,096 items/final runs, 8,192 shaping calls, 16 MiB total copied
shaping-context bytes, 262,144 total attempt glyphs and 256 nested brackets.
Wrapping additionally admits at most 1,024 selected lines and 4,096 candidate-line
evaluations. The same shaping call/byte/glyph counters cover the entire wrapped
operation; they do not reset for each candidate or line. The final run budget
covers all selected lines together. The glyph budget counts every attempt,
including discarded attempts; one native
call still has the font component's one-million-glyph bound before the paragraph
can inspect its result. Budget failures throw without returning partial success.
These are bounds, not production allocation/throughput measurements.

Tabs/hard line separators, hyphenation/emergency breaking, alignment, styled spans, arbitrary shaping features,
OS fallback, full international typography, visual cursor navigation, core
editing/IME integration, GPU composition and browser visual comparisons remain
open. See [the initial receipt](../../docs/evidence/graphics/2026-10-27-paragraph.md)
and [wrapping evidence](../../docs/evidence/graphics/2026-10-27-wrapping.md).
Editing geometry has its [own receipt](../../docs/evidence/graphics/2026-10-27-editing-geometry.md).
