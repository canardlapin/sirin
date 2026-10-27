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
| Implemented | Build skeleton and the generation-checked `Handle` type, nothing else. |
| Platforms | macOS arm64, verified locally. Windows x64 and Linux x64 are planned and CI-only. No interactive Windows verification is possible yet. |

## Smallest example

There is no public UI API yet. The design direction includes an
*illustrative* API sketch in [`docs/design/VISION.md`](docs/design/VISION.md)
§4. That sketch is not implemented code.

## Build and verify

Requires a JDK, sbt, and clang/LLVM, as for Scala Native 0.5.12.

```sh
sbt testJVM       # core, engine, testkit on the JVM
sbt testNative    # the same suites compiled with Scala Native
```

The native probes need pinned prebuilt artifacts:

```sh
native/fetch-macos.sh   # wgpu-native v29.0.1.1 and SDL3 3.4.18, checked by SHA-256
```

## Documentation

- [Design direction](docs/design/VISION.md)
- [Roadmap and Stage 0–1 plan](docs/plans/2026-10-27-roadmap.md)

## Known gaps

Almost everything is still to be built; see the roadmap. In particular,
there is no window, renderer, text system, or widget yet, and Windows
behavior is unverified.
