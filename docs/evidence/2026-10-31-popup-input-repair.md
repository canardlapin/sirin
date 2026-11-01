# Popup input repair, 2026-10-31

The owner resumed work after the [review](2026-10-31-popup-review.md), with the
goal of a usable macOS/JVM workbench and continued Scala Native work. This
successor repairs the reported D9 and D13 violations. The original two failing
tests and their raw receipts remain in history at `04fc28c`.

## Behavior

- Input eligibility follows the last presented modal layer independently of
  stored focus. A text-only modal or a focus-loss veto cannot leak Enter,
  Space, editing keys, text or composition events to the underlying control.
- Focus-loss vetoes still work. Stored focus becomes eligible again after the
  modal closes; programmatic and assistive requests cannot focus or operate
  a covered control. Composition is retired when its target becomes blocked.
- Tab uses the active modal layer even when stored focus belongs to an older
  popup. Opening or closing a layer retires pending key presses. A base
  element's captured pointer receives Cancel when a modal covers it, and never
  receives the later release.
- Local shortcut ancestry follows nested anchors through earlier layers back
  to the base. The innermost matching binding, including a disabled binding,
  retains precedence. Blocked base focus supplies no local shortcut ancestry;
  window and application chords remain available.

The design note clarifies D9's unsuccessful-focus-move case and D13's nested
ancestry. Transactions, owner identity, lifetime, scheduling, focus veto and
command policies are unchanged. No native entry point or ABI changed.

## Verification

On macOS arm64 using sbt's explicit JDK 25.0.4.1 selection:

| Check | Result |
|---|---|
| `testJVM` | 447 passed |
| `testNative` | 447 passed |
| `desktopJVM/test` | 124 passed |
| `probeParagraph/test` | 40 passed |
| `examples/compile` | passed |

Shared tests exercise the original nested routing failure, disabled-inner
precedence, modal activation, press cancellation, Tab from a lower popup,
non-modal control behavior and captured-pointer cancellation. Desktop tests
exercise the original real-renderer modal failure, blocked text/composition
and assistive input, window chord fallback, focus veto, Escape and restored
input after closing. Existing test assertions were not weakened.

The full run passed after focused checks. A single line was wrapped for style
during the Native part of the full run; there was no behavior change after
the JVM checks. The package build recompiles that formatting-only successor.

Full log: `/private/tmp/sirin-popup-repair-full.log`.
SHA-256: `7167f75fb3997a69009e5e9577551625d8189084014902cc0b9bac0980d7e0bc`.
The earlier focused run's test-only private-access compile error is retained
in `/private/tmp/sirin-popup-repair-focused.log`; the corrected desktop focused
run is `/private/tmp/sirin-popup-repair-desktop-focused.log` (40 passed).

This is automated input qualification, not real-window or VoiceOver acceptance.
The native accessibility bridge still needs checkbox/dialog publication. Native
desktop delivery, the GC safepoint gate and the Linux reconciliation diagnosis
remain open. The broader [workbench plan](../plans/2026-10-31-workbench-acceptance.md)
is not complete.

Mote repairs: `bd-01M6AEC8KR5PJHF7DG82NE1A9H`,
`bd-01M6AEC8TMSKRYQDJPPSWWBB1X`.
