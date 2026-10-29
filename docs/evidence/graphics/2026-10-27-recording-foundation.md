# Standalone vector recording foundation

Date: 2026-10-27 (America/Toronto). Local macOS arm64 checkout based on
`91a0ceb`, with concurrent uncommitted kernel/reconciliation work preserved.
This receipt covers the new `graphics` cross-project and the linked recording
contract, not the whole working tree or a graphics renderer.

## Implemented scope

- JVM/Scala Native `sirin-graphics`, with no UI/core, engine or native library
  dependency. Root test aliases and aggregation include the module.
- Finite Double coordinates and affine composition/inversion; immutable paths
  containing moves, lines, quadratic/cubic curves, closes and rectangle helpers.
- Explicit tagged sRGB colors, solid/linear-gradient paints, stroke styles and
  validated dash patterns; no implicit device scaling or premultiplication.
- Immutable ordered recordings retaining shared paths/paints and child drawings;
  flat transform/clip/isolated-opacity boundaries and ordered instance columns.
- Scope rollback after construction failures, expired-canvas/builder rejection,
  and clearing of temporary construction buffers.
- A public-only standalone diagram specimen with reusable content, a dashed
  curve, gradient, ordered marks and selection overlay.

See [the module contract](../../../graphics/README.md) for the precise provisional
semantics and [the graphics plan](../../plans/2026-10-27-graphics-api.md) for gates.

## Verification

Toolchain: Scala 3.7.4, Scala Native 0.5.12, sbt 1.12.14, JDK 21.
No native vendor artifact was fetched/built or loaded by this module.

The graphics suites pass **23 tests on each platform** (GeometrySuite 6,
ValuesSuite 7, DrawingSuite 10), with no ignored tests:

- Explicit matrix/ordering references and ScalaCheck properties for composition
  and inverse round trips, including shear/reflection/rotation/translation; singular,
  extreme uniform scale, large-origin precision and nonfinite-input cases.
- Path/control-point/subpath validation, expired construction capabilities,
  paint anchoring/stop ordering, alpha preservation and dash-period validation.
- Explicit command sequences for translucent order, established clips and nested
  layers, shared drawing/batch references, failed-scope rollback and subsequent
  recovery, invalid opacity and per-instance cardinality.
- The standalone public consumer compiles on JVM and Native without UI services.

Commands and local logs:

```sh
sbt 'graphicsJVM/test' 'graphicsNative/test'
# /private/tmp/sirin-graphics-foundation-tests-20261027.log
sbt testJVM testNative
# /private/tmp/sirin-graphics-foundation-all-tests-20261027.log
sbt testNative
# /private/tmp/sirin-graphics-foundation-native-all-tests-20261027.log
sbt 'graphicsJVM/test' 'graphicsNative/test'
# /private/tmp/sirin-graphics-foundation-final-tests-20261027.log
```

The initial targeted graphics run passed on both platforms. After the final
dash-overflow check and specimen/test refinement, `testJVM` again passed all
23 graphics tests and the existing 7 core tests, then stopped compiling the
concurrently edited engine test: `ReconciliationSuite.scala:508` uses a local
`Key` type test, producing an unchecked warning fatal under `-Werror`.
Consequently the combined command did not start `testNative`; it was invoked
separately and passed all 23 updated graphics tests and the existing 7 core tests,
then stopped at the same warning in the Native engine test compilation. Neither
full alias passed. The following testkit suites did not run because the aliases
stop at that failure. Kernel/test sources were not changed by the graphics task,
and no warning or test requirement was weakened.

The final targeted run, after strengthening affine properties to arbitrary
matrices and adding shear to inverse round trips, again passed all 23 graphics
tests on both JVM and Native. Local Markdown links/fences and whitespace checks
pass. These final graphics results are independent of the blocked engine tests.

## Limits

No backend, tessellation, rasterization, GPU submission, prepared resource cache,
font/image resource service, UI bridge or Intaglio adapter is implemented by this
slice. Command checks cannot establish antialiasing, clip coverage, pixel color,
gradient interpolation, isolated-alpha output or performance. Backend image,
resource and timing checks are not yet executable because the backend does not
exist; no passing placeholders stand in for them. Windows/Linux and interactive
UI behavior were not verified here. No full capability row or G-0–G-4 gate is
closed by this receipt.
