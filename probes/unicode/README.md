# P0.8: isolated Unicode analysis probe

This artifact-dependent Scala Native probe qualifies ICU4X through a pointer-only
C shim and a small Rust C ABI adapter. It depends on standalone `graphics` data,
without the UI runtime, wgpu or AccessKit. It is not a production text layout
engine. Do not add it to the host:
VISION §6.1 requires the combined `sirin-native` library before co-linking Rust
components. This executable has exactly one Rust static library.

```sh
native/fetch-macos.sh --unicode
sbt probeUnicode/test
```

The fetch recipe requires Cargo, Python 3 and a C toolchain, checks disk space,
verifies pinned source/full-library downloads, and builds a locked minimal
properties-and-segmenter static library into ignored `native/vendor/unicode/`.
The source pin is ICU4X 2.4.0 commit
`031d9e89f6a3fd26a14491e090729d46094328fd`; generated headers come from that same
archive. The full upstream compiled-data archive is a size baseline only and
is not linked. No locale-data filtering or matched ICU4C comparison is claimed.
The recipe supports macOS arm64 and x86_64; only arm64 has local qualification.

The generated C API selects older grapheme and line rules despite containing
Unicode 18 data. A small Rust adapter exposes the pinned upstream `new_neo`
grapheme/line constructors through a bounded pointer-only C ABI in the same
library. These constructors require ICU4X's `unstable` feature. Word segmentation
and raw bidi use the generated C API. The line adapter also accesses that API's
actual Rust `BidiInfo` to expose L1-adjusted levels and L2 source maps absent from
the reordered-string interface. Its algorithm dependency is exactly
`unicode-bidi` 0.3.18, already used by this ICU4X pin, with ICU4X's property data
rather than a separate hardcoded Unicode table. All algorithms remain upstream code;
there are no Sirin Unicode-rule patches. Upgrading this pin requires rerunning
the reference suite, not merely substituting a newer archive.

`NativeUnicode.open()` owns native segmenters and bidi data until explicit
`close()`. It is confined to its creating Scala thread, with no finalizer or
foreign-thread callback. Use workers for slow analysis. The C shim contains all
Diplomat structs, including slices, options and by-value results. Potentially
long/allocating externs are `@blocking`, listed in VISION §5.4. Input UTF-8 lives
in native storage for the call; every iterator and bidi object is destroyed
before returning. Scala copies all result arrays before native result retirement.
The owned analysis remains valid after engine closure.

Input is valid Unicode with a configurable UTF-8 budget of at most 1 MiB. Code-unit
length is checked before encoding; encoded bytes are checked again. Native
arrays and output counts are bounded. The default results provide:

- Typed source UTF-8/UTF-16 mappings and grapheme boundaries/ranges.
- Default Unicode word boundaries with number/letter/non-word classification.
- Strict, normal-word Unicode line boundaries, with structural start/end markers.
- Raw resolved bidi levels **per UTF-8 byte**, paragraph ranges and direction
  classification, for auto/LTR/RTL requested paragraph direction.

There is no locale/dictionary tailoring. Thai or other complex-script word quality
is not qualified by default-rule conformance. Structural line offset zero is not
a wrapping opportunity. Bidi classification describes LTR/RTL/mixed content; it
is not the base embedding level. Levels are before line-dependent UAX #9 L1/L2;
they do not supply visual reorder, glyph placement, caret positions or selection
geometry. Never reverse UTF-8 bytes to reorder text.

`resolveLine(analysis, range, paragraphIndex = 0)` adds an owned `BidiLine` for
one caller-selected line in an existing paragraph. It preserves whole-paragraph
context and the requested direction instead of analyzing the substring as a new
paragraph. Ranges and returned codepoint locations use source-global UTF-8 byte
offsets. They must be codepoint-aligned and contained in the chosen paragraph;
the caller must choose legal wrapping boundaries separately.

Each logical `BidiCodepoint` retains its exact source range and L1 level, or
`None` for an X9 omission. `visualOrder` is a permutation of the non-X9 codepoint
starts after L2. `paragraphLevel` is the actual base embedding level (0/1),
distinct from `BidiDirectionClass`. Supplementary codepoints retain their entire
UTF-8/UTF-16 span. Empty ranges inside a real paragraph are supported; empty
source has no bidi paragraph and rejects this operation. No blank-line metrics
or carets are invented.

This is not reordered display text: L3 combining behavior, L4 mirroring, script
itemization and glyph shaping remain for the shared shaping/layout pipeline.
Removing X9 characters from the visual map does not remove them from the source
or prescribe editing behavior. Joiners/controls remain available as shaping
context; do not rebuild shaper input from the visual map. Line results retain
their immutable analysis and
outlive service closure. The prototype recomputes native paragraph analysis per
line, then copies the result before retirement; repeated-line preparation costs
and a reusable native paragraph owner are not qualified.

`UnicodeAnalysis` is standalone immutable data, with validated codepoint-aligned
boundaries and explicit Unicode/engine metadata. The version label comes from
the pinned release, rather than a runtime version-query API. It does not shape
fonts or introduce a second UI text layout implementation. Paragraph itemization,
wrapping, shared UI editing layout and GPU text remain later work. The separate
[paragraph component](../paragraph/README.md) uses these properties and line
results for real script itemization, explicit supplied-face fallback and shared
unwrapped placement, without co-linking a UI host.

`properties(source)` exposes pinned Script/Script_Extensions, paired-bracket and
default-ignorable/variation-selector data through bounded allocation-free scalar
lookups. `visualOrder(levels)` exposes upstream L2 for already-resolved object/run
levels (at most 65,536); it allocates and is `@blocking`. Neither operation chooses
font faces or substitutes a second Unicode table.

Tests verify the checked-in [Unicode fixtures](src/test/resources/unicode/SOURCES.md)
before loading them. Segmentation compares the complete default-rule reference
files. Raw-level tests retain the original reference prefix; a separate line
suite compares the complete 91,707-record BidiCharacterTest corpus through L2,
plus wrapped-line context and source mapping. This does not qualify glyph/L3/L4
behavior or the separate BidiTest class-sequence corpus. See the
[evidence note](../../docs/evidence/graphics/2026-10-27-unicode.md) for results,
sizes and remaining P0.8 gates, and the
[line-bidi receipt](../../docs/evidence/graphics/2026-10-27-bidi-lines.md).
