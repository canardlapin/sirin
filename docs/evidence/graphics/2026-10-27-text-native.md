# Static-font shaping and grayscale coverage component

Date: 2026-10-27 (America/Toronto), macOS arm64. The shared checkout advanced
through concurrent kernel commits during this task; HEAD was `397e20f` when this
receipt was finalized, with uncommitted graphics work. See the local test log and
Git history for context. This task preserves unrelated work. This is partial
P0.3b/G-0/G-1 evidence, not full shared text, GPU rendering or Stage 0 acceptance.

## Artifact and fixture receipt

`df -h` showed approximately 203 GiB available before building. The fetch recipe
also checks for at least 2 GiB on every invocation before any native build.
Artifacts were fetched/built through `native/fetch-macos.sh --text`, into ignored
`native/vendor/`, with Meson 1.7.0, Ninja 1.12.1 and Apple Clang 16.0.0. Libraries are static,
minimal and independent of Homebrew font libraries; no source dependency was
built before checking disk space.

| Input | Pin | SHA-256 of downloaded source archive |
|---|---|---|
| HarfBuzz 14.5.0 | `863d3f7787c6df18d20e4535c5906bf3eb803bd5` | `35d6211b9310646c8d6b7c06d697439efd26d787ec8fff2508e6b936836ca492` |
| FreeType 2.15.0 | `37515cf56ba3349656049edbed89ffc6f6466f2d` | `9efee8b0508e142f38d1c7e2e192d3e5041aa4bbf6c2ab1a901518211f76a914` |

The fetch script pins commit-addressed GitHub source archives. Hashes were
computed from those downloads; no independent release signature verification is
claimed. Sources: [HarfBuzz](https://github.com/harfbuzz/harfbuzz/tree/863d3f7787c6df18d20e4535c5906bf3eb803bd5)
and the [official FreeType mirror](https://github.com/freetype/freetype/tree/37515cf56ba3349656049edbed89ffc6f6466f2d).
The recipe disables optional OS shapers, compression/Unicode dependencies,
HarfBuzz raster/vector/GPU engines, subsetters, utilities and Rust backends;
FreeType does not depend on HarfBuzz. Upstream library licenses are copied into
the installed vendor prefix.

Static Noto Sans Regular and Noto Naskh Arabic Regular fixtures come from
`notofonts/noto-fonts` commit `ffebf8c1ee449e544955a7e813c54f9b73848eac`.
[Their source/ownership note](../../../graphics/src/test/resources/fonts/SOURCES.md),
SIL OFL license and `SHA256SUMS` are checked in with the unmodified fonts.
`textNative/test` verifies those hashes before running. Runtime library versions
are checked against the actual HarfBuzz and FreeType libraries, not just headers.

## Implemented component

- Standalone owned font/source/run data in `graphics`, with typed UTF-8/UTF-16
  source offsets, real glyph positions/advances/ink and retained font bytes.
- Separate artifact-dependent `textNative` service with a pointer/scalar-only C
  shim. All native structs stay in C; Scala mirrors no structs or callbacks.
- Explicit static face/index/size, horizontal script/direction/language and
  standard-ligature feature; missing glyph zero remains visible in the result.
- Exact glyph-run recording without a second shape/layout, and unique font-file
  dependency/retained-byte inspection across recordings.
- Unhinted grayscale FreeType masks from the same bytes/face/glyph IDs, with
  1/64 horizontal phase, device-scale preparation, bearings and owned coverage.
- Explicit owner-thread confinement and close; copied run/mask results outlive
  the native face. Variable/color font faces and unsupported raster paths fail
  explicitly. See [the service contract](../../../text-native/README.md) for limits.

No new scheduler, transaction, app-state identity or UI lifetime policy was added.
Native parsing/metrics/shaping/raster/retirement calls are `@blocking` and listed
in VISION §5.4. Their C paths never call Scala and create no threads. Count,
version, metadata and borrowed-pixel-pointer accessors are bounded plain externs.
The wrong-thread test uses a Scala-created thread; it is not a new foreign-thread
entry experiment and does not supersede the existing P0.6/P0.7 evidence/gates.

## Verification

Local logs:

```sh
native/fetch-macos.sh --text
# /private/tmp/sirin-text-artifacts-20261027.log
sbt graphicsJVM/test textNative/test
# /private/tmp/sirin-text-native-tests-20261027.log
sbt testJVM testNative textNative/test
# /private/tmp/sirin-text-final-tests-20261027.log
```

The first compile attempt exposed a Scala Native `alloc` size-type mismatch;
it was corrected to the supported Int overload before running native tests.
The final full command passed. Graphics has 41 passing tests on each platform,
including 4 new Unicode/source/font-data checks. The real font service has 7
passing native tests with no ignored tests:

1. Pinned library versions, actual font loading, metrics, face count and invalid
   font rejection.
2. Actual OpenType `ffi` substitution in `office`, feature-off glyph count, source
   clusters, positive advance and ink geometry.
3. Combining-mark clustering, Greek coverage, explicit missing Arabic glyphs in
   the Latin font, and covered Arabic RTL clusters in the Arabic font.
4. Empty input has no invented glyph, advance or ink.
5. HarfBuzz ink versus independent FreeType mask bearings/width (1.1-pixel bearing
   and 2.1-pixel width tolerances), nonzero coverage, shifted coverage at 0.25 phase,
   larger masks at 1.5× and unchanged logical advances; empty space mask.
6. Recorded run/font dependencies and copied masks survive explicit face closure;
   exported coverage cannot mutate the owned mask; close is idempotent and later
   native use is rejected.
7. Other-thread use, invalid glyph/raster/phase inputs and raster budget rejection.

The existing core, engine and testkit suites also passed on JVM and Native in the
shared checkout. `textNative/test` is intentionally outside root aggregation and
the ordinary test aliases, since it requires explicitly fetched native artifacts.
A dedicated macOS CI job now fetches/builds and tests it. That CI job has
not been executed remotely in this session. Linux and Windows service behavior
are unverified/unsupported as described in the service contract.

## Copies and open qualification

Font/source import and exports copy. Native opening copies font bytes through a
managed export and native zone into face-owned native storage. Shaping copies
source bytes and result records; rasterization copies FreeType coverage into a
shim-owned packed buffer and then into Scala-owned storage. No pointer crosses a
borrow lifetime. Inventory `fontBytes` counts unique retained source payload,
not temporary copies, native allocations, atlas memory or uploads.

There is no automatic font discovery/fallback, variable/color font support,
paragraph bidi/itemization/wrapping, grapheme/caret/selection topology, core text
adapter, GPU atlas/composition or visual browser comparison. Source offset maps
are codepoint boundaries, not editing geometry. These absent checks have no
passing placeholders. P0.3b's GPU glyph/golden requirement, all general graphics
gates, platform qualification and the existing pause gate remain open. Test times
are not rendering-performance or memory-leak measurements.
