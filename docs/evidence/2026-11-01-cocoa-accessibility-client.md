# Cocoa client checks and linked-description repair

The repeated failure of an automation-tool AX click to focus Title prompted a
test of the actual Cocoa objects produced by the pinned AccessKit library.
`desktop/tools/test-accessibility-client.sh` builds a native-only test over a
hidden SDL window. It does not activate a window, synthesize OS input, query
another application, or call Scala from a foreign thread.

## Findings

The TextField's native Focus setter queues the exact revision, semantic ID and
action kind. It has no button Press action, while the Save button does. The
focus setter does not mutate the published tree. After the owner publishes
focus, the focused property is visible only while the adapter's view-focus input
is true. The test drives that input explicitly; the hidden window never becomes
the key window. This establishes the native focus boundary, not actual keyboard
delivery or VoiceOver navigation. The automation tool's generic click has not
been shown to send this Focus request, so calling its failure a proven Sirin
focus defect would be too strong.

The client test found a separate production defect: a field with `describedBy`
and no direct description had empty Cocoa Help. Its linked title worked. The
failure was reproduced against actual native objects and retained as
`client-expanded.log`; it was not inferred from dependency source. Cached Rust
source was consulted as a lead, but its patch version differs from the linked
library and is not the verification authority.

The bridge now retains the relationship and supplies a direct description from
its accepted textual target when no explicit source description exists. Label
values take precedence over Label names; other targets supply their name. This
uses the already bounded immutable snapshot and introduces no ABI or runtime-pin
change. The [design note](../design/decisions/2026-11-01-cocoa-description-fallback.md)
records the policy and alternatives.

## Regression coverage

The repaired client assertions check linked title and Help, explicit-description
precedence, Label-value fallback, clearing after relationship removal, native
checkbox role/checked state, ignored disabled actions, exact focus/press queue
addresses, and safe retained native objects after target retirement and adapter
closure. The existing native protocol additionally checks that the actual
AccessKit tree carries the description while preserving `describedBy`.

Initial test-fixture attempts lacked required grapheme geometry and then queried
`accessibilityLabel` instead of Cocoa's `accessibilityTitle`. Their failures and
the diagnostic tree are preserved. Those setup errors are separate from the
subsequent reproducible missing-Help assertion. No assertion was weakened to
make the production defect pass.

The frozen-source native client and protocol scripts both exited 0. The complete
command, with JDK 25 explicitly selected, was `sbt testJVM testNative
desktopJVM/test probeAccessibilityNative/run examples/compile
desktopJVM/desktopWorkbenchPackage`. It exited 0: 450 JVM, 450 Scala Native and
142 desktop tests passed, the Native probe reached all nine shared ABI v4 entry
points, examples compiled and the production workbench packaged. Source hashes
still matched after packaging and UI exercise. The paragraph suite was not
rerun for this native-description-only change; no shaping or geometry changed.

## Fresh-package native action check

The unconfigured image passed the portable-image dependency, font and signing
checks with `--load-library`. The new production package was installed at the
previously approved isolated path and signed again. The older package is retained
in `cocoa-client-previous-package`. A Native executable reached ABI v4 in the
exact installed dylib; the loader log identifies that path.

Finder launch and opening Acceptance Alpha showed the exact saved title/body.
The computer-use tool's native `setValue` action changed Title to "AX client
acceptance": the field, sidebar and window title updated, and Save enabled.
Setting Title empty through the same action exposed the error as Cocoa Help
and a separate text entry, painted a red field border and error message, and
disabled Save. Restoring the original title removed the error and enabled Save.
This verifies a real packaged accessibility value-action round trip through
the owner and presentation. It does not establish Focus behavior through this
tool, and the workbench's field has an explicit description; linked-only Help
is proved by the separate native Cocoa client.

Command-Q presented the dirty modal, excluding background document controls.
Discard and quit exited the acceptance app. The unrelated Sirin application was
left running. The isolated document inventory and exact bytes were unchanged;
the original document SHA-256 remains
`1e717858dda45fc34d230f0c58294cd96d9a286e0ed0a8776bdd51fa86a37d28`.

## Scope

Raw logs and receipts are under `/private/tmp/sirin-accessibility-client-20261101`.
The manifest `artifacts.json` has SHA-256
`ba66b22e25a34a4dd4f672468a4391e8b2ae942ab5907635029fda98f1f2356a`.
It covers the original fixture/production failures, successful client and
protocol runs, full-suite log, frozen-source hashes, package closure and signing,
exact packaged ABI loader output, UI observations and document receipts.
The installed JAR has SHA-256
`8552601b5a8e0537fcd4dc0a9b1d50d87257124303b4e877ba9d50ebfc8d85f7`;
the dylib has SHA-256
`ecbe8bb467c5528d7c7b502ba33b1f6c28f7c18bea6d565029c98a8e7ddf4f1f`.
Real VoiceOver and IME, physical input, semantic-part focus, the Native application
provider and P0.6 remain open. The shared JVM and Scala Native tests and same-ABI
Native probe continue to be required; a hidden native Cocoa client is not a
Scala Native application provider.
