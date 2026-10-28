# Sirin

Sirin is a desktop UI engine for Scala Native, built from the ground up.
Scala owns the interface semantics: state propagation, component identity
and lifetime, layout, input, text editing, accessibility, and rendering
strategy. Native libraries supply the lower-level services:

- SDL3 for windows and events,
- wgpu-native for GPU access,
- HarfBuzz and FreeType for shaping and rasterization,
- AccessKit for accessibility transport.

> This is pre-release development software. APIs, semantics, and package
> boundaries may change. No stable support or binary-compatibility promise
> should be inferred unless the package explicitly states one.

## Status

| | |
|---|---|
| Lifecycle | `prototype` |
| Publication | Not published. Source only. |
| Implemented | Build, handles, standalone graphics recording/resources/preparation, a bounded real offscreen wgpu pixel probe, native static-font shaping/coverage, Unicode analysis, shared text geometry, core text and Canvas recording bridges with prepared text. A bounded packaged macOS/JDK25 host supports keyboard/pointer commands, focus, retained moves and virtual scrolling. Its action-latency gate remains open. Headless kernel progress and qualification are tracked in the roadmap and evidence notes. |
| Platforms | macOS arm64, verified locally. Windows x64 and Linux x64 are planned and CI-only. No interactive Windows verification is possible yet. |

## Smallest example

For a standalone vector recording example, see [`graphics/README.md`](graphics/README.md).
It needs no UI runtime or GPU. The provisional UI API and authoring corpus are
described in [`docs/design/VISION.md`](docs/design/VISION.md) §4; they do not yet
provide a complete windowed UI.

## Build and verify

Requires a JDK, sbt, and clang/LLVM, as for Scala Native 0.5.12.

```sh
sbt testJVM       # graphics, core, text-ui, graphics-ui, engine, testkit, workbench
sbt testNative    # the same suites compiled with Scala Native
```

The native probes need pinned prebuilt artifacts:

```sh
native/fetch-macos.sh   # wgpu-native v29.0.1.1 and SDL3 3.4.18, checked by SHA-256
```

## Documentation

- [Responsive JVM host candidate and remaining gates](docs/evidence/responsive-host-20261028/README.md)
- [Design direction](docs/design/VISION.md)
- [Roadmap and Stage 0–1 plan](docs/plans/2026-10-27-roadmap.md)
- [Graphics module and recording contract](graphics/README.md)
- [Offscreen graphics and ThorVG comparison probe](probes/render/README.md)
- [Isolated Unicode analysis probe](probes/unicode/README.md)
- [Wrapped and unwrapped paragraph component](probes/paragraph/README.md)
- [Core text adapter and unit/affinity contract](text-ui/README.md)
- [Core Canvas recording and prepared text](graphics-ui/README.md)
- [General graphics capability and quality plan](docs/plans/2026-10-27-graphics-api.md)
- [Native font/shaping component](text-native/README.md)

## Known gaps

Almost everything is still to be built; see the roadmap. In particular,
the windowed renderer, complete text/layout system and widgets remain unfinished, and Windows
behavior is unverified.
