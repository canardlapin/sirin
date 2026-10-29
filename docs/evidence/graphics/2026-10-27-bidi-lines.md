# Source-preserving line bidi component

Date: 2026-10-27 (America/Toronto), macOS arm64, Scala Native 0.5.12.
HEAD at verification: `a821946`, with uncommitted graphics work and concurrent
project work preserved. Mote child: `bd-01M602FREZN7RANXHXYJMFW861`.
This extends the [Unicode checkpoint](2026-10-27-unicode.md); it does not complete
shared text layout, the public graphics capability gates or full P0.8.

## Implemented boundary

`NativeUnicode.resolveLine(analysis, range, paragraphIndex)` produces standalone
immutable `BidiLine` data. The line's global UTF-8 range must be codepoint-aligned
and contained in the chosen paragraph. The native operation reuses the complete
source and requested direction when rebuilding bidi information, preserving
paragraph context instead of reinterpreting the line substring as a paragraph.
It applies upstream UAX #9 L1 and L2 and returns:

- Actual paragraph base level, distinct from the earlier direction class.
- Logical codepoint ranges and line-adjusted levels, with explicit `None` for
  X9 omissions and no deletion from the original source.
- A visual map of source-global UTF-8 codepoint starts, permuting all non-X9
  codepoints exactly once. It never reverses UTF-8 bytes or UTF-16 surrogate halves.
- The original immutable analysis, retained without rebinding source/metadata.

The Scala result validates its paragraph/range, exact source-codepoint partition,
levels and visual permutation. Empty ranges in a real paragraph are supported.
Empty source has no bidi paragraph and explicitly rejects this operation; the
component supplies no invented blank-line metrics or carets. Legal wrapping
boundaries remain the layout caller's responsibility.

This is not a reordered string or glyph drawing order. UAX #9 L3 combining
behavior, L4 mirroring and shaping are outside this component. Joiners and other
X9 codepoints remain available in the original shaping context; rebuilding
shaper input from the visual map would be incorrect. Editing/caret policies are
not inferred from codepoint indices. Reference:
[UAX #9, Unicode 18 revision 52](https://www.unicode.org/reports/tr9/tr9-52.html).

## Native implementation, pins and ownership

The existing pin remains ICU4X 2.4.0 commit
`031d9e89f6a3fd26a14491e090729d46094328fd`, with generated headers from the same
verified source archive. `unicode-bidi` **0.3.18**, already used by that ICU4X
pin, is now an explicit dependency with default features disabled. Cargo.lock
pins its registry checksum
`5c1cb5db39152898a79168971543b1cb5020dff7fe43c8dc468b0885f5e29df5`.
Native resolution uses ICU4X's property-backed C API, not a new hardcoded property
table. No dependency version or Unicode-rule patch was introduced.

The generated API offers reordered strings without the L1 levels/source map
needed by the layout pipeline. The bounded Rust C ABI adapter accesses the actual
upstream `BidiInfo` created by that API, calls its `reordered_levels`, records X9
omissions and calls upstream `reorder_visual` on remaining codepoint levels.
Scala and C never mirror Rust object layouts. Diplomat by-value structs stay in
the C shim; Scala externs use scalars/pointers only. Both paths live in the same
static library, without co-linking wgpu or AccessKit. VISION §6.1 documents this
additional use of the pinned upstream adapter.

`su_line`/`su_line_free` are `@blocking` and listed in VISION §5.4; bounded count
and array-pointer accessors remain plain externs. Native input lives in a zone
until bidi objects are destroyed. Bounded native output arrays are copied into
Scala before explicit retirement. No callbacks, finalizers, foreign-created
threads or retained borrowed inputs were added. Owner-thread confinement, source
≤1 MiB byte budget and explicit closure remain unchanged. A service with a
smaller budget rejects analysis retained from a larger-budget service.

The prototype recomputes paragraph analysis per line and copies a full adjusted
level buffer internally. A reusable native paragraph owner/batch operation and
costs for many lines remain unqualified. No throughput, allocation or UI latency
claim follows from the reference suite's runtime.

## Reference and focused tests

`BidiLineSuite` loads every one of the **91,707** records in the unchanged,
checksum-verified Unicode 18 `BidiCharacterTest.txt`. It asserts:

1. Resolved paragraph base level.
2. Every logical codepoint's post-L1 level, including exact X9 `x`/`None` entries.
3. The complete post-L2 visual source-codepoint index vector.

All records pass. No exceptional cases are filtered, ignored or replaced with
engine-generated expected data. The existing 16-example raw-level test remains
unchanged; it is not substituted for line-level testing. The separate BidiTest
class-sequence corpus, L3/L4 and glyph output are not certified by this receipt.

Three additional tests cover embedded RTL whitespace reset at a wrap while raw
paragraph levels stay unchanged; X9 retention/omission; empty selected ranges;
preserved paragraph context versus independently analyzing the substring;
global ranges in later paragraphs; supplementary and combining codepoints;
invalid/continuation-byte/cross-paragraph ranges; invalid paragraph selection;
empty-source rejection; smaller source budgets; other Scala-created thread
rejection; result survival after closure; and use-after-close rejection.

The existing eight Unicode tests also pass, including all **22,143** grapheme,
word and line-break reference records. CI's existing macOS Unicode job runs the
new suite via `probeUnicode/test`; no remote CI execution is claimed here.

Local commands and logs:

```sh
native/fetch-macos.sh --unicode
# /private/tmp/sirin-bidi-artifacts-20261027.log
sbt graphicsJVM/test probeUnicode/test
# /private/tmp/sirin-bidi-tests-20261027.log
sbt testJVM testNative probeUnicode/test textNative/test
# /private/tmp/sirin-bidi-final-tests-20261027.log
cargo fmt --manifest-path native/unicode-probe/Cargo.toml --check
bash -n native/fetch.sh native/fetch-unicode.sh
git diff --check
```

The complete command passed with no ignored tests: graphics 41, core 7, engine
103 and testkit 25 on each of JVM and Native; the Unicode probe 12 and real font
service 7 on Native. Fixture record counts are assertions within those tests,
not separate MUnit test counts. Rust formatting, shell syntax and whitespace
checks passed as well.

## Artifact receipt and remaining work

`df -h .` showed ~202 GiB free before the native build. The fetch script's ≥2 GiB
check ran before compilation. With the same Rust 1.95.0/profile/cache as the
earlier checkpoint, rebuilding the adapter reported 4.57 s. This is an incremental
build receipt, not a cold build or a trimmed-locale-data cost.

| Artifact | Bytes |
|---|---:|
| Component static archive with line adapter | 9,878,872 |
| Debug probe test executable, including test infrastructure | 13,174,960 |
| Test executable `__TEXT` | 8,552,448 |
| Test executable `__DATA_CONST` | 983,040 |
| Test executable `__DATA` | 344,064 |

Sizes come from `wc -c` and `/usr/bin/size -m`. The 4 GiB `__PAGEZERO` virtual
reservation is not file payload or measured resident memory. Neither table-data
costs nor runtime memory are isolated; the prior full archive remains a different
build profile, not a fair ICU4C comparison. Only macOS arm64 was exercised.

Next text work is script itemization, real face fallback and positioned paragraph
layout shared by drawing and the eventual core editing adapter, including proper
combining/mirroring, wrapping and caret/selection topology. Locale/dictionary
policy, data filtering/ICU4C comparison, production combined `sirin-native`, GPU
text/composition, other platforms and browser comparisons remain open. Existing
native-host and pause gates are preserved; no kernel semantics changed.
