# Script itemization, supplied-face fallback and unwrapped paragraph placement

Date: 2026-10-27 (America/Toronto), macOS arm64, Scala Native 0.5.12.
HEAD at verification: `a821946`, with uncommitted graphics work and concurrent
kernel work preserved. Mote child: `bd-01M6035MPTRK5X25KBCDR2CV52`.
This is the bounded three-part text slice authorized by the owner. It advances
G-0/G-1; it does not clear full graphics, P0.3b/P0.8 or native-host acceptance.

This records the first unwrapped checkpoint. The subsequent
[wrapping receipt](2026-10-27-wrapping.md) extends the shared line representation,
shaping context and layout behavior; statements below describe this checkpoint.

## Implemented result

1. **Script itemization.** Dynamic four-letter script tags replace the original
   four-case enum. ICU4X supplies Script, Script_Extensions, paired-bracket and
   default-ignorable/variation-selector data. Scala's documented shaping policy
   resolves Common/Inherited using compatible surrounding scripts, keeps a
   grapheme's base script, preserves whole graphemes and assigns closing brackets
   their opener's script. Items split at script or resolved bidi-level changes.
   An unsupported visible level boundary inside one grapheme rejects explicitly.
2. **Explicit fallback.** Caller-supplied immutable face snapshots determine all
   choices. The first face covering the complete script/level item wins. If none
   does, fallback atoms are whole graphemes enlarged across shaping clusters from
   every full-item attempt. First-covering faces are selected for atoms, adjacent
   identical choices are merged, and final ranges are reshaped. Uncovered atoms
   retain real notdef glyphs and source-range diagnostics; no invented font or
   estimate hides missing text. Variation selectors remain explicitly unsupported
   even if HarfBuzz silently discards one.
3. **Unwrapped placement.** Standalone immutable `ParagraphLayout` retains source,
   analysis, script items, bidi information, requested faces, logical glyph runs,
   visual run placements, baseline, advance/ink bounds and issues. Logical items
   and runs partition source. Measurement reads stored metrics; `record` emits
   the exact same runs and positions through public `Canvas2D.glyphs`.

The result is the nucleus of shared drawing/editing layout, not a separate UI
text engine. No caret/hit/selection methods, core adapter or successful UI stub
was added. Full policy and limits are in the
[component contract](../../../probes/paragraph/README.md).

## Shaping context and native boundary

`NativeFont.shapeRange` supplies the full logical paragraph with an item byte
range to HarfBuzz, setting actual source-edge BOT/EOT flags. Clusters are global
source UTF-8 offsets; glyph positions remain run-local. Every fallback attempt
has this same context. Source is never reversed or rebuilt from a codepoint
visual map, and attempt glyph arrays are never spliced into final output.
This follows the upstream [HarfBuzz context guidance](https://harfbuzz.github.io/adding-text-to-the-buffer.html).
Script itemization is a declared higher-level shaping policy over pinned data;
[UAX #24](https://www.unicode.org/reports/tr24/tr24-41.html) supplies the property
and context reference, not a single mandated itemization implementation.

The paragraph integration executable links **one** ICU4X Rust static library
with the existing HarfBuzz C++ and FreeType C archives, without wgpu/AccessKit or
their Rust runtimes. Scala/C never mirror Rust objects or pass Diplomat structs
by value. The small `su_script_info` ABI is bounded, allocation-free static table
access with at most 64 Script_Extensions entries; it is a plain extern. Allocating
object-level L2 ordering uses the C shim and `@blocking su_order`. Font shaping
remains `@blocking sg_text_shape`. VISION §5.4/§6.1 inventory these additions.
No callbacks, foreign-created threads, finalizers, kernel scheduling/identity or
transaction/lifetime changes were introduced.

Native services are explicit caller-owned, creating-Scala-thread-confined
resources. All input/output borrow lifetimes end before a call returns. Owned
layouts retain face/source data and survive service closure. Native shaping
copies full source context for every attempt, then copies glyph results back.
The layout retains all requested face inputs; recorded drawings retain only
actual used font snapshots. These are explicit copy/dependency contracts, not
zero-copy, allocation-rate or throughput evidence.

Default paragraph work limits are 64 KiB source, 16 distinct supplied faces,
4,096 items/final runs, 8,192 shape calls, 16 MiB accumulated copied context,
262,144 accumulated output glyphs across all attempts, and 256 nested brackets.
One native call still has the font component's one-million-glyph limit before the
paragraph can inspect that result. Budget failure returns no partial success.
The caller can configure bounded limits. Tabs/hard line separators and negative/
vertical advances reject rather than receiving invented layout behavior.

## Pins, fixtures and artifacts

ICU4X remains 2.4.0 commit `031d9e89f6a3fd26a14491e090729d46094328fd`, with
headers and compiled data from the same checksum-verified source. A direct
`icu_properties` dependency selects the already-present pinned component; no new
Unicode algorithm/version or second Rust library was added. Cargo.lock is checked
in. HarfBuzz 14.5.0 and FreeType 2.15.0 pins remain as recorded in the
[font receipt](2026-10-27-text-native.md). Existing Unicode 18 reference fixtures
and their checksums remain unchanged.

Two unmodified static fonts were added from the same `notofonts/noto-fonts`
commit `ffebf8c1ee449e544955a7e813c54f9b73848eac` as the existing fixtures:

| Font | Bytes | SHA-256 |
|---|---:|---|
| NotoSansHebrew-Regular.ttf | 26,900 | `a7fa16fffb27bedb060a0866267c29e9859aeb9c21cc33f5b3aaf6eb062eca85` |
| NotoSansDevanagari-Regular.ttf | 219,212 | `385e78e6359a9d88a0f243d53b1209d7548361ba2194e2b9ec779bcaa7e8949d` |

Their [source note](../../../graphics/src/test/resources/fonts/SOURCES.md),
SIL OFL license and SHA256SUMS are preserved with the actual fonts. Font and
Unicode hashes are checked by `probeParagraph/test` before execution. These are
download receipts, not independent signature verification.

`df -h .` showed ~202 GiB free before source compilation. Artifacts were built
through `native/fetch-macos.sh --unicode`, whose ≥2 GiB check ran first. Rust
1.95.0/profile/cache are unchanged; the property-adapter rebuild reported 4.35 s.
The final ICU4X archive is **9,879,864 bytes**. The debug paragraph test executable
is **15,805,904 bytes**, including Scala/test/C/C++/Rust code and compiled data.
`otool -L` reports only system libc++ and libSystem dynamic dependencies.
Those facts do not isolate table costs, establish app packaging, measure RSS or
provide a matched trimmed ICU4C comparison. Other platforms remain unqualified.

## Verification and retained negative result

The first integration suite passed 7/8 tests. Its intended successful partial-font
fallback case used `∑־`, but U+2211 was absent from both supplied faces. The pipeline
correctly reported `MissingGlyphs` for source bytes `[0,3)`. The test's coverage
assumption was wrong; no algorithm correction or weakened acceptance was used.
The case now uses `±־`, with explicit assertions that the first face covers only
the first symbol and the second only the Hebrew punctuation. It demands complete
output, the expected two fonts and exact global source partition `[0,2),[2,4)`.
The [initial failure log](paragraph-20261027/initial-fixture-failure.log) is retained.

Eight integration tests now pass with real pinned fonts:

1. Common/Inherited/Script_Extensions, a leading combining mark, Japanese shared
   script punctuation, bracket context, Unknown script and actual ignorable data.
2. Context-sensitive Arabic joining in separately shaped logical ranges versus
   a whole-source reference, plus actual `ffi` substitution and global clusters.
3. Latin/Greek/Arabic/Hebrew/Devanagari paragraph face choices, combining/conjunct
   input, common baseline, advance/ink geometry, real FreeType mask coverage,
   exact public recording and used-font dependency inspection.
4. RTL visual run ordering with logical source intact, real mirrored punctuation
   in both the run service and placed paragraph, and level/direction agreement.
5. Successful within-item fallback with disjoint real face coverage.
6. An uncovered mixed-script base/mark grapheme remains unsplit and diagnosed;
   unsupported emoji/variation selectors stay explicit; `ffi` survives nearby
   missing text.
7. Empty/space-only metrics come from real faces; owned mixed-script layouts and
   recordings survive native closure.
8. Invalid Unicode, hard breaks/tabs, work/input/face/item/bracket limits,
   owner-thread checks and closed-face rejection.

The complete command passed with no ignored tests:

```sh
sbt testJVM testNative textNative/test probeUnicode/test probeParagraph/test
```

Counts: graphics **41**, core **7**, engine **103**, testkit **25** on each of
JVM and Native; font service **7**, Unicode **12**, paragraph **8** Native tests.
The existing Unicode tests still assert all **22,143** segmentation and **91,707**
BidiCharacterTest records. Rust formatting, shell syntax and `git diff --check`
passed. [Verification log](paragraph-20261027/verification.log),
[source hashes](paragraph-20261027/manifest.json) and
[build log](paragraph-20261027/unicode-build.log) are retained.

The existing macOS font CI job now builds the Unicode component and runs the
paragraph suite too. Remote CI has not been executed in this session. Native
test success is not visual browser parity, platform matrix or performance
qualification.

## Remaining work

Wrapping and styled spans; real caret/hit/selection topology and the shared core
adapter; OS font discovery/fallback epochs; variation/color/UVS fonts; broader
script, language, quote-pairing and typography qualification; reusable preparation
and measured allocation/upload costs; GPU text/composition and visual browser
comparisons. Full P0.8 data-filtering/ICU4C and existing host/pause gates remain
open. The completed text slice is usable without pretending those later features
already exist.
