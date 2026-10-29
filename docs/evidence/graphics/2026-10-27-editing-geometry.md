# Shared caret, hit-testing and selection geometry

Date: 2026-10-27 (America/Toronto), macOS arm64, Scala Native 0.5.12.
Mote child: `bd-01M605RV15P0N1983VRK81K06T`.
Checkout at start: `606f45d`, with preceding graphics and unrelated work retained.
This extends [word wrapping](2026-10-27-wrapping.md); it does not complete
the core text adapter, editing/IME, GPU text, P0.3b/P0.8 or host qualification.

## Implemented contract

`TextLineLayout`, `ParagraphLayout` and `WrappedParagraphLayout` now expose
lazy `TextGeometry` derived from their stored runs and exact line placements.
It may be prepared for the first time after font/Unicode services close. Its
queries perform no font lookup, native calls, fallback, reshaping or re-layout.
Recording still emits the identical `ShapedRun` objects used for measurement.

Editing atoms intersect HarfBuzz cluster starts with pinned ICU4X extended
grapheme boundaries. This merges shaping clusters that split a grapheme and
never inserts a caret inside combining sequences, emoji, UTF-8 continuations
or UTF-16 surrogate pairs. Each resulting editing cell covers one complete
grapheme and preserves its global source range, including missing text and
invisible controls. `TextSource` additionally maps typed UTF-16 codepoint
boundaries back to UTF-8; editing queries then check grapheme boundaries.

`TextPosition` carries logical upstream/downstream affinity: preceding trailing
edge or following leading edge respectively. These preserve distinct x positions
at bidi transitions and distinct line positions at wraps. Both affinities use
the one available edge at outer source endpoints. Empty text has two affinities
at x=0 with real line height and no invented glyphs.

Caret bars are zero-width and full-line-height, separate from ink bounds. Hits
clamp to the line's half-open vertical band (the lower line wins exactly at a
shared edge), then choose the nearest horizontal stop. Downstream and then
source order break ties; all other collocated/equidistant stops remain in
`TextHit.alternatives`. This is an explicit nearest-stop policy, not visual
arrow-key navigation or bidi strong/weak cursor behavior.

Selections require ordered grapheme endpoints within the queried layout.
Invalid carets return None; invalid selections throw without snapping.
Selected advance cells are unioned per line into visual rectangles, including
spaces and preserving bidi gaps. Zero-width controls add no filled area.
Regions are selection backgrounds, not partial-glyph ink masks/recoloring.
Line geometry is local; wrapped geometry includes each actual line origin.
Hosts transform input/geometry using the same transform as drawing.

The public contract and query details are in
[the paragraph README](../../../probes/paragraph/README.md#editing-geometry).
The provisional core API still needs an explicit policy for affinity and hit
ambiguity; it is not filled with an independent width estimator.

## Ligature policy and native boundary

The pointer/scalar-only `sg_text_carets` shim queries pinned HarfBuzz's
`hb_ot_layout_get_ligature_carets`, copying at most 64 GDEF coordinates per
single-glyph, multi-scalar shaping cluster. It retains metadata only when the
count matches scalar count minus one; retained caret storage is bounded by
input scalar count. The result is copied before the native run is freed.
The new extern is `@blocking` and inventoried in VISION §5.4. It adds no callback,
foreign-created thread, finalizer, Rust library or kernel policy.

For single-glyph atoms with one scalar per grapheme, geometry adds usable GDEF
coordinates to the stored shaped glyph origin, including positioning offsets.
Counts must match, positions must be distinct and strictly inside the shaped
advance span, and RTL reverses logical association. This uses positioned glyph
coordinates rather than treating unshaped GDEF values as layout-global metrics.
The [HarfBuzz API contract](https://harfbuzz.github.io/harfbuzz-hb-ot-layout.html#hb-ot-layout-get-ligature-carets)
describes those raw coordinates;
[HarfBuzz's cluster model](https://harfbuzz.github.io/working-with-harfbuzz-clusters.html)
explains why a cluster can contain multiple graphemes and glyphs.

Otherwise, interior grapheme positions divide the **actual shaped atom advance**
equally. `CaretEdgeKind` distinguishes real cluster edges, usable font ligature
carets and explicit interpolation; selection propagates an `interpolated` flag.
No character-width estimate replaces shaping or determines line breaks.
Interpolated positions are policy-defined geometry, not measured font carets or
evidence of browser-equivalent typography. Font-specific editing tailoring,
variable fonts, styling and full international editing remain unqualified.

The pinned Noto Sans `ffi` has GDEF coordinates 315/631 font units: at logical
size 24, HarfBuzz returns 7.5625/15.140625 after 1/64 quantization. Tests assert
these independently read fixture values and prove that partial selection uses
them rather than equal thirds. `of\u200Dfice` actually forms a ligature with a
retained joiner and exercises the explicit interpolation route. Feature-off
`office` uses actual separate cluster edges.

No external dependency was rebuilt. The existing paragraph executable still
combines one ICU4X Rust library with C/C++ HarfBuzz/FreeType and no wgpu/AccessKit.
Production co-linking, delivery/platform evidence and pause gates remain open.

## Verification

Executed successfully, exit 0, with no ignored tests:

```sh
sbt testJVM testNative textNative/test probeUnicode/test probeParagraph/test
```

| Suite | JVM | Native |
|---|---:|---:|
| graphics | 41 | 41 |
| core | 7 | 7 |
| engine | 123 | 123 |
| testkit | 28 | 28 |
| workbench specimen | 5 | 5 |
| textNative | — | 7 |
| probeUnicode | — | 12 |
| probeParagraph | — | 27 |

Total: **454 passing tests**. Unicode retains the full pinned 22,143 segmentation
and 91,707 bidi reference records inside those tests. The paragraph total is
8 paragraph + 9 wrapping + 10 editing-geometry tests. JVM geometry compiles in
the dependency-free module; real font/Unicode integration runs on Native.

After that full run, extra assertions were added to the interpolation test to
check its one-third/two-thirds coordinates against the independently summed
actual glyph advance. No production code changed. The final test source passed
`sbt 'probeParagraph/testOnly *EditingGeometrySuite'`: **10 passed**, exit 0,
none ignored. The checkout remained `606f45d` with uncommitted work.

Raw final logs, the failed fixture premise, final code/test/document hashes and
command receipts are retained in
[`editing-geometry-20261027/`](editing-geometry-20261027/manifest.json).
`otool -L` on the final paragraph test executable lists only libc++ and libSystem
as dynamic libraries; this is test-link evidence, not a shipping artifact or
bundle-size/RSS claim. `git diff --check` passed and the updated local
documentation links resolved. CI configuration includes this suite through the
existing paragraph task; no remote CI execution is claimed.

The focused geometry suite first passed nine tests; the final suite adds a
nearest-stop/transform case. Coverage includes real fonts, ligatures/GDEF and
explicit interpolation, RTL Arabic, mixed bidi dual carets and discontiguous
selection, combining/Indic/emoji graphemes, missing glyphs, invisible/X9
controls, wrapping/global mappings, empty/overflowing lines, exact-coordinate
hit/caret agreement, per-cell selection/caret agreement, invalid endpoints,
transformed input, native closure and unchanged recorded run identity.

The first focused run had one failed fixture assumption: `سلام لا` contains
separate clusters and needs no interpolated interiors. The assertion was
replaced with the actual ligature-with-joiner case, retaining the interpolation
requirement. The original failed log is preserved alongside the final evidence;
it was a test premise failure, not permission to weaken glyph/grapheme behavior.

No pixels, browser comparison, interactive editor, IME, GPU atlas or performance
claim is established here. Geometry preparation retains O(source + glyphs)
data, with sorting during preparation; hit queries index line bands and x stops.
Selection scans cells and sorts/merges the selected intervals. Allocation,
latency and production editing workloads remain unmeasured.
