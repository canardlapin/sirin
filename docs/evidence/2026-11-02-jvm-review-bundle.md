# JVM review preparation and bounded Linux reconciliation review

## Claim and scope

Four JVM alpha applications are prepared in durable local storage for later owner
review: production workbench, matched ScalaFX baseline, an independently built
ReviewPad starter and the production inspector-enabled workbench. They contain
clean published product code from `6a13aea9e666beaadef7b2d8f3c9841de8774914`.
This tranche changes preparation tooling and documentation, not product/kernel
semantics, Native runtime policy or the C shim.

The owner chose **prepare it for later review**. These four fresh packages were
not launched during this tranche. Visible workflows, physical input and owner
acceptance remain pending. Earlier window evidence retains its own identities
and scope; it does not qualify these fresh copies.

## Preparation and checks

Artifacts live at:

```text
/Users/bbuchsbaum/code/scala/sirin-artifacts/jvm-acceptance-20261102
```

The [guide](../guides/jvm-acceptance.md) gives exact relative app/data paths,
reproduction commands and review tasks. `START-HERE.md`, `protocol.json` and an
unfilled `review-results.md` are included in the local bundle. Review results
distinguish visible, physical and owner judgments from preparation receipts.
The local copies use absolute document paths and must be regenerated if moved.

- `desktopJVM/desktopWorkbenchPackage` and
  `scalafxWorkbench/scalafxWorkbenchPackage` complete successfully under JDK25.
- SDK export records the clean source commit and validates the native closure,
  bundled fonts and signatures, including library loading without opening UI.
- Only tracked `examples/starter` files were copied into an independent build.
  Its four tests, package and app preparation pass against that exported SDK.
- Each copied app has a distinct bundle identifier and an explicit synthetic
  document/file argument. The inspector adds only `-Dsirin.inspector=true`.
  No delay wrapper or timing comparison is included.
- All four `codesign --verify --deep --strict` checks pass. Every packaged file
  matches the recorded app inventory. Application JARs and the native transport
  match their source packages after signing. The three Sirin copies have native
  transport SHA-256
  `049c65622136dee9e643018414865c8c43fc444e08924f982011df7edb223b95`.
- The final preparation helper parses successfully and refuses both nonempty
  and directly symlinked output without modifying the sentinel/target. The
  original helper used for the successful four-app generation is retained
  separately from the final helper with stronger preflight/post-sign checks.
- Required local `sbt testJVM testNative` completes with exit zero: 476 JVM and
  476 Native tests pass. Module totals on each runtime are
  `69, 27, 2, 9, 223, 36, 38, 64, 8`; no failures/errors are reported.
  `build/regression.log` retains the complete output. Desktop/ScalaFX behavior
  is covered by the published base CI; no fresh window acceptance is inferred.

The three workbench roots start with byte-identical Alpha files, SHA-256
`1e717858dda45fc34d230f0c58294cd96d9a286e0ed0a8776bdd51fa86a37d28`.
Import fixtures contain that existing ID and one distinct valid ID. A malformed
document is isolated in another directory for preparation refusal. These are
review inputs, not successful import observations. No documents were edited
through the applications.

The small [artifact index](2026-11-02-jvm-review-bundle.json) records the durable
manifest and receipt/log hashes. App contents are recursively hashed in
`receipts/apps.json`; original fixture hashes are in
`receipts/fixtures-before.json`. The local manifest is a preparation snapshot:
later review will intentionally change results and document files. Raw apps,
SDK, build outputs and logs remain outside Git.

## Linux ClassCastException review

The bounded review compares the original public CI failure at `5c73273` with the
guarded published product CI at `6a13aea`:

| Evidence | Historical failure | Current guarded run |
| --- | --- | --- |
| Run | [40974637787](https://github.com/canardlapin/sirin/actions/runs/40974637787) | [41559816141](https://github.com/canardlapin/sirin/actions/runs/41559816141) |
| Linux job | `123799537300` | `125667460791` |
| Reported OS/image | Ubuntu 26.04.1, `ubuntu-26.04` | Same reported OS/image |
| Image version | `20261025.179.1` | `20261025.179.1` |
| Actions runner | `2.338.0` | `2.338.2` |
| Reconciliation | Native type error | Both suites pass 4/4 on JVM and Native |

The current Linux job passes all 476 shared tests on each runtime; testkit has
36 on each. The full workflow's nine jobs pass at the exact published source
commit. These are existing CI results, not a newly scheduled Linux experiment.
Full old/current logs, current run/job metadata and a verified copy of the
original freeze are retained in `linux/` beside `linux/review.json`.

The original seed remains
`l8Dl-ZJbwAFzUVsay_UCl1zuWxTHh-oHefe7es6XzbN=`. Its frozen program has 40 steps;
SHA-256 is
`8d14f2b40ae38d06b04be0a1f2aa585e0db4445d6b17110258dd67c00e236a3e`.
The original failed-step log still hashes to
`d36d3870fb8173c80adcb2c8f4e8dde566d10fda0f26e2fac002c3cd1bcf4edd`.
The historical log names `ArrayBuffer$$Lambda$1` cast to `IterableOnce` but
contains no useful underlying application stack for attribution.

Source inspection confirms the randomized minimum remains 300. The frozen
program and seeded suite remain additional checks. A captured
`ClassCastException` is rethrown, and the first underlying exception is printed
before shrinking. The regression verifies propagation of the same captured
exception instance; ordinary refusal/expected poison controls remain intact.

Matching reported images strengthens environment relevance but does not make
these a controlled comparison: source, runner version, hardware, kernel and
scheduling conditions are not held constant. A pass establishes current failure
visibility and compatibility for the executed programs, not the historical
cause or a repair. No evidence-based engine fix follows from this review.
`bd-01M65371ESG6B9B7S82RNE1R1W` stays open. A recurrence should retain the new
first-exception stack, exact seed/program and environment before changing code.

## Remaining gate

The next session is the prepared human/visible review, including matched ScalaFX,
starter and inspector windows. VoiceOver, real IME, trackpad/pointer delivery,
display-scale/owner visuals and delayed foreground presentation remain open.
All Alpha1–6 remain open. This preparation does not qualify Native latency or
the separate Native provider work.
