# Independent public consumer and development SDK

ReviewPad is a separate sbt consumer under `examples/starter`, intended to be
copied outside the framework checkout. Its application imports only `sirin.*`
and JDK APIs. It implements a persistent title, multiline notes and review flag,
with asynchronous load/save, validation, failed-open retry and guarded quit.
The local SDK is exported from the existing verified macOS/JDK25 app image and
records a complete file/link inventory. The consumer has no source-project link
to Sirin. This does not constitute a published SDK or Native application provider.

## Candidate evidence

The initial copy at
`/private/tmp/sirin-public-starter-20261101/consumer-candidate` built from
`sdk-candidate` with `sbt clean test packageApp` under JDK 25. All three storage
tests passed and `SirinReviewPad.app` packaged with a valid deep/strict signature.
The tests cover exact Unicode/review-state persistence, stale-save conflict
preservation, corrupt and oversized files, invalid edits and malformed UTF-16.

The candidate SDK explicitly records its dirty source checkout. It is not the
clean-checkout qualification. Final source is committed before the separate
clean framework checkout and copied consumer are built.

Packager controls accept the original SDK and refuse a changed JAR, an unexpected
JAR and a mismatched JDK before packaging. Their receipts and the expected
wrong-JDK failure are under `/private/tmp/sirin-public-starter-20261101`.

A packaging check with `test documents/review.note` exposed `jpackage` splitting
the configured path into two arguments. The starter now writes the launcher's
one-property-per-argument CFG section and signs the modified image. The generated
configuration contains exactly `--file` and the complete path, and signing
verification passes. The original split CFG, corrected CFG and receipt are
retained. Real launch remains a separate required check.

## Clean source and independent consumer

A new local clone at `framework-clean` checked out framework commit
`dbc72ca149ba52e4b6aba307b66d65d1df26edf8` with no tracked changes or prior build
outputs. It reused the existing pinned native prebuilts through `native/vendor`;
this was not a fresh native dependency build or a cold dependency-cache test.
The before-build receipt records the combined library and SDL input hashes.

Under JDK 25, `sbt testJVM testNative examples/compile
desktopJVM/desktopWorkbenchPackage probeAccessibilityNative/run` exited 0:
450 JVM and 450 Native tests passed, examples compiled, the app image packaged,
and the Native executable reached all nine ABI v4 entries. The checkout remains
clean. Exporting that new image produced `sdk-clean/sdk.json` with the exact
framework commit and `source_dirty=false`; native closure, fonts, signing and
library loading were checked by the exporter.

Eight tracked starter files from consumer commit
`d2c749d979b2b5bccbed2e67620227ec4498684d` were copied to `consumer-clean`, outside
both framework checkouts, with no build output. The intervening consumer commit
only repairs argument packaging; the framework binary source is unchanged.
With only `SIRIN_SDK` configured, `sbt clean test packageApp` passed all three
storage tests and packaged the app. `show Compile/fullClasspath` contains its
own classes, the copied SDK JAR and the two Scala runtime JARs. It contains no
framework source or build-output path. The main application imports only public
`sirin.*` and JDK APIs. All copied source hashes still match their committed files.

The default image passed portable dependency/font/signature checks with library
loading. It is preserved in `consumer-default-package`. The isolated image at
`/private/tmp/sirin-reviewpad-acceptance-20261101/SirinReviewPad.app` is configured
with exactly two arguments: `--file` and
`/private/tmp/sirin-reviewpad-acceptance-20261101/test documents/review.note`.
The note did not exist before acceptance. The copied image passes deep/strict
signing, and a Native executable passed ABI v4 against its exact dylib, confirmed
by the loader log. `acceptance-package.json` records application, framework,
native-library and launcher hashes.

## Malformed-text regression and final consumer

A subsequent negative test exposed an input validation gap: Java properties
can decode ASCII `\uD800` into an unpaired surrogate even when the file bytes
are valid UTF-8. The original loader accepted it. The consumer now validates
paired UTF-16 and excludes NUL in both title and body before publishing a loaded
note or encoding a save. The regression covers escaped high/low surrogates and
NUL, failed-open write refusal and exact file preservation. Invalid edits cover
both fields; valid supplementary Unicode still round-trips. The copied candidate
passes all four tests after the fix. Original failure and repaired run are kept
as `escaped-text-before.log` and `escaped-text-after.log`.

A fresh external copy at `consumer-final` contains the eight tracked files from
`0f33b3877086c39402cc8004192371f95922ed04`. With the unchanged clean SDK, its
`sbt clean test packageApp 'show Compile/fullClasspath'` exited 0 with all four
tests passing. The source hashes still match the commit; imports and classpath
retain the public binary boundary. Portable closure/font/library-loading checks
passed. The default package is preserved in `consumer-final-default-package`.
The isolated image at the previously specified acceptance path was replaced by
this repaired consumer, and its prior image is retained as `acceptance-d2c749d.app`.
Deep/strict signing passes. The Sirin JAR, native library and configured arguments
are byte-identical to the already checked isolated image, so the exact-library
Native ABI result remains applicable. The new application JAR is
`5fb1013f001106b1ac33c3ac3544abe64871e6d945f19d20c7f3b2a7dc186b99`.
ReviewPad has not been launched and its test note is still absent.

## Approved workbench window check

The approved SirinWorkbench image was launched again. It opened the isolated
Acceptance Alpha document with its exact saved text. Emptying Title showed the
required-title error both visibly and as native field Help, with Save disabled.
After restoring Title and making an unsaved body edit, Command-Q opened the
dirty-quit dialog. Escape retained the exact draft. Reopening the dialog and
choosing Discard and quit exited the app, verified by the running-app inventory.
The saved document retains SHA-256
`1e717858dda45fc34d230f0c58294cd96d9a286e0ed0a8776bdd51fa86a37d28`.
This check uses native accessibility edits and keyboard input; it does not claim
VoiceOver, IME or physical trackpad acceptance.

## Receipts and remaining verification

Raw logs, source hashes, package checks and the window observation record are in
`/private/tmp/sirin-public-starter-20261101`. The 34-file receipt manifest has
SHA-256 `90ce8dbcadb63a0049fa32f1b4b21d9d79222f541a5f13adb4609809dc1cfd0f`.
The shared checkout's tracked-diff hash matches the preserved baseline; source
work remains confined to the owned branch and external validation copies.

Real-window approval and workflow acceptance are pending. The new app has a
distinct identity and test-file location. The computer-use confirmation policy
requires approval to launch this new locally built app; the earlier approval
covered SirinWorkbench. No launch or editing/save/reopen result is claimed here.
The inspector, independent workbench review, matched ScalaFX workflow and Native
delivery gates remain open. The starter's storage is JVM-specific; shared Native
tests and an ABI probe do not establish a Native starter/provider.

## Subsequent launch and SDK refresh

The owner subsequently approved the isolated ReviewPad launch. Its controls
appeared, but the first batched input attempt reported an unexpected exit.
Relaunch showed prolonged black content and slowly advancing title input.
No save/reopen acceptance is claimed and the cause remains unresolved. The
original package and negative observations are preserved. A new external copy
of the unchanged consumer builds against the current verified `1a9baa3` SDK,
passes all four storage tests and packages with an isolated path and distinct
identity. It has not been launched. See the
[window observations and refresh receipts](2026-11-01-production-window-acceptance.md).
