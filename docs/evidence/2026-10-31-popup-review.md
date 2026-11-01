# Checkbox and Popup follow-up review, 2026-10-31

Reviewed Checkbox `169b2e0` and Popup `149a836` in the isolated worktree
`/private/tmp/sirin-popup-review-20261031`. No production code or design
semantics were changed. The two added regression tests deliberately retain
their failing assertions; this review branch is not a passing landing candidate.

## Repository state

Local and remote main initially pointed to `52df66f`. During verification,
another process fast-forwarded and pushed main to `149a836`. This review did
not perform that merge or push. The diagnostic source remained at `149a836`.

## Existing checks

Before adding diagnostic tests:

| Check | Result |
|---|---|
| `sbt testJVM` | 442 passed |
| `sbt testNative` | 442 passed |
| `desktopJVM/test` | 121 passed |
| `probeParagraph/test` | 40 passed |
| `examples/compile` | passed |

JVM and Native ran with the launcher's default JDK 21.0.12.1. Desktop
compilation then failed because that JDK lacks the required final FFM methods.
Setting `JAVA_HOME` alone did not change this sbt launcher's JVM. The remaining
checks passed with `sbt --java-home` pointing explicitly to the installed
Temurin 25.0.4.1 JDK. The unsuccessful attempts are retained below.

## Confirmed defects

1. **Modal keyboard isolation (D9).** Focus a base button, then open a modal
   popup containing only `Text`. There is no focusable popup target, so
   `EditorController.followPopups` leaves focus in the base. Pressing Space
   activates that button: the observed count is 2, expected 1. The public
   controls, real renderer and controller are exercised in the added
   `PopupSeamSuite` test. Its five existing tests pass; the new test fails.
   Mote: `bd-01M6AEC8KR5PJHF7DG82NE1A9H`.
2. **Nested shortcut ancestry (D4, D13).** `KeyboardRouting.local` searches
   only the base tree for a popup's anchor. If the anchor belongs to a parent
   popup, a child popup cannot bubble to the parent's shortcut. The new
   `PopupReviewSuite` passes its one-level control, then returns `None` where
   the parent activation is expected. This is a host routing test over the
   captured tree representation, not a real-window input test.
   Mote: `bd-01M6AEC8TMSKRYQDJPPSWWBB1X`.

Diagnostics used JDK 25:

```text
hostJVM/testOnly sirin.host.PopupReviewSuite: 0 passed, 1 failed
desktopJVM/testOnly sirin.desktop.PopupSeamSuite: 5 passed, 1 failed
```

Implementation stopped under AGENTS.md: "If code and documents disagree, stop
and report." Both repairs remain open. No real window, trackpad, VoiceOver,
or new Native diagnostic run was attempted. Existing native-accessibility,
DesktopWorkbench routing and modal dimming limits remain.

## Receipts

Raw logs are local under `/private/tmp/`; they are not committed.

| Log | SHA-256 |
|---|---|
| `sirin-popup-review-baseline.log` | `a433fec4a6c166af2b004ff7d394ec47c6d9303e55e29794ca7b52ba16a68669` |
| `sirin-popup-review-desktop-baseline.log` | `e94f9429376483ab6971a63bbfd715a0b64ece71450c09c1f3fc1ac7d0fe3250` |
| `sirin-popup-review-jdk25-baseline.log` | `e6ca60d7fc1eac180327b83a56cc6760146847b0be1ed5251f4b5be9bd496d95` |
| `sirin-popup-review-nested-shortcuts.log` | `c250f8158e0beb8c4a7d81a72a231373d6661416e5b798c859c39e9fe7efc86f` |
| `sirin-popup-review-modal-keyboard.log` | `48e61ab1f6bc54fdb4d4a7cb4f4f1eb643a34b22c7abfaed1cb5cc3acb0404d1` |

Review task: `bd-01M6ADQZCK9M9PKHZ4Q12SDRJV`.

The owner subsequently resumed implementation. See the
[repair successor](2026-10-31-popup-input-repair.md); the failures above remain
the historical result for `149a836`.
