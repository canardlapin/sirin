# Exact semantic-part focus

`NodeRef.focusPart` previously raised `KernelError.Deferred`, `focusedPart`
was always `None`, and native focus applied only to a custom element's first
semantic node. A new shared regression reproduced the deferred failure before
implementation. The original failing output is preserved in `before.log`.

The existing transactional focus queue now carries an optional part ID. The host
resolves it against a directly referenced focusable element's accepted semantic
parts, rejecting missing, clipped, ambiguous and modal-excluded targets without
redirecting them. Part focus publishes with the existing reference focus signals.
It does not select or activate a part, change element-level Tab order or bypass
a focus-loss veto. Native focus retains the exact semantic address through owner
delivery. No ABI entry point or managed foreign-thread callback was added.

The interaction state retains the part during disablement and native deactivation;
deactivation clears the published signals until reactivation. Accepted removal
clears the part while retaining a surviving element's focus. Popup restoration
retains the prior part if it remains exposed, otherwise restores the element.
Custom elements with an activation declaration now receive their normal focus
hooks as well. The design update is recorded in VISION and
`docs/design/decisions/2026-11-01-semantic-part-focus.md`.

Part-only changes advance the frame request even if no application signal reads
`focusedPart`, ensuring a fresh native publication. Base rendering strips this
host metadata from its cache key. A renderer regression checks reuse of the same
layout, identical pixels and no additional shaping for a part-only change.

## Regression scope

Shared engine/host checks cover rollback, queued target retention, atomic
publication, detachment, exact and ambiguous lookup, modal exclusion, disabled
focus retention, element-level Tab/pointer behavior, part retirement and new
presentation generations. Desktop owner tests cover native revision checks,
selection independence, Lost/Gained behavior, veto rollback, deactivation,
part clipping/removal, disabled native requests and exact popup restoration.

An expanded in-process Cocoa client uses two actual graphic accessibility objects
in a hidden SDL window. Focusing the second queues revision 106, semantic ID 7,
action kind 1. Native focus stays on the first until owner publication moves it
to the second. The first expanded fixture failed after publication because
publication refreshes actual key-window state and the test window is hidden.
Supplying active-view state after each publication, as in its existing field
test, fixes the fixture. The failed and corrected logs are retained. This is
native-client evidence, not VoiceOver or foreground window activation evidence.

The first compilation after changing internal focus-request shape also exposed
two old recovery-test call sites; both now use the exact-request controller path.
No existing assertion was removed or relaxed.

A final public-intent regression exposed a pre-existing host coalescing error:
requests for part 2, then 1, then 2 finished on part 1 because deduplication kept
the first occurrence. The host now retains the last occurrence of an identical
intent. The failed test output is preserved as `focus-order-before.log`.

## Verification and remaining acceptance

The complete required run passed 454 JVM, 454 Native and 147 desktop tests,
examples compilation, packaging and all nine Native accessibility ABI entries.
After the queue-order repair, both engine focus suites passed again and the full
desktop suite passed all 148 tests; examples and packaging passed again. Native
protocol and the expanded Cocoa client tests also pass. No threshold was changed.

The default package passes portable dependency/font/signature/library-loading
checks. The isolated acceptance image intentionally contains an absolute test
document argument, so the generic portable-config check refuses it; that failed
check is retained, and the checker was not weakened. The isolated image passes
deep/strict signing. Its final native library differed from the first signed
image, so the Native executable was rerun against the exact final dylib. The
loader log confirms that path and all nine ABI entries pass.

In the approved workbench window, keyboard traversal reached Title, dirty quit
opened its modal decision, and Escape restored focus. One first-image check
accepted continued typing exactly and discarded without modifying the saved
file. The final-image check exposed an additional timing failure: Escape followed
immediately by typing ` restored` produced `Final focus checkstored`. A repeat
with `abcdefghi` produced `Probe bcdefghi`; observing the restored field before
typing produced the full `Observed abcdefghi`. Do not count this as complete
input acceptance. The missing-prefix handoff is a separate open defect, not
explained or repaired by the part-focus tests. All test drafts were discarded.

Artifacts are under `/private/tmp/sirin-part-focus-20261101`. Source snapshots,
original failures, suite logs, both package receipts and the exact window
observations are retained there.
The 66-file manifest has SHA-256
`0b606d3a5dedda695cbd25e94a849367a7d6c126be29b23f5ace52cb10527746`.
The prefix-loss defect is tracked as `bd-01M6AZSB5Q1G40KYAS1EQY1BYB`.
Actual VoiceOver, IME, physical input, independent ReviewPad window acceptance,
the inspector, matched ScalaFX comparison and Scala Native application delivery
remain separate open gates.
