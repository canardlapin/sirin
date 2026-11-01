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

## Remaining verification

Clean-checkout framework verification, fresh SDK export, independent consumer
build/package and real-window acceptance are pending. The new app has a distinct
identity and will require its own launch approval under the computer-use policy.
No UI launch is implied by building the package. The inspector, independent
workbench review, matched ScalaFX workflow and Native delivery gates remain open.
