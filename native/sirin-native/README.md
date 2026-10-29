# Combined macOS development runtime

From the repository root, fetch the normal artifacts and text/Unicode/ThorVG
artifacts as before, then run `bash native/fetch-macos.sh --sirin`. The combined
runtime currently targets macOS arm64 only and requires Cargo/Rust and the Apple
SDK. The fetch script checks at least 8 GiB free before building.

This crate emits the single Rust static library used by `desktopJVM`:
wgpu-native, AccessKit C and the existing pinned ICU4X Unicode adapter share one
Cargo graph and standard library. The component probes continue to run in their
own processes with their original libraries. The desktop no longer links the
wgpu dylib or standalone ICU4X archive. The desktop FFM library has process
lifetime; AccessKit's installed Objective-C class methods must not be unloaded.

The recipe verifies the wgpu-native v29.0.1.1 source archive, AccessKit C0.23.1
release archive and WebGPU header by SHA-256. Their final native-only crate types
are changed to `rlib` in ignored vendor sources, allowing this parent to emit the
only static library. Upstream sources are otherwise unchanged. `Cargo.lock`
freezes the combined graph, including wgpu-core/hal/types/naga29.0.4 and
AccessKit0.25.2/macOS0.27.2; these resolved patch releases differ from the earlier
prebuilt probe graph and require their own desktop rendering checks.

AccessKit's C header is included only by the pointer-only Objective-C shim.
The shim creates AccessKit nodes/updates and consumes owned action requests;
managed code passes only native buffers and scalar facts. Activation reads a
native snapshot. Actions enter a bounded native queue and wake SDL outside its
lock. The macOS adapter detaches before the userdata is released.

Licenses remain in fetched source directories: wgpu-native's MIT/Apache-2.0,
AccessKit's MIT/Apache-2.0 and ICU4X's Unicode-3.0. Unicode-bidi is MIT/Apache-2.0.
No native binary or system font is committed. This local app image refers to
vendor SDL paths and is not a redistributable package.

Headless protocol checks: `bash desktop/tools/test-accesskit.sh`.
Full integration checks and qualified limits are in
`docs/evidence/text-editor-20261029/README.md`.
