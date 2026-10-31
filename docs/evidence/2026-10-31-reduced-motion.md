# Following the macOS Reduce Motion setting, 2026-10-31

## Claim

The application host reads the macOS Reduce Motion accessibility setting. It
reads it at startup and on each loop turn, and applies it to the runtime and the
development theme. Everything that motion does under reduced motion follows
from that. The [motion decision](../design/decisions/2026-10-30-motion.md)
describes the rest.

## How

- **Native shim.** `sirin_menus_reduce_motion` in `desktop/native/menus.m`
  returns `NSWorkspace.sharedWorkspace.accessibilityDisplayShouldReduceMotion`.
  `sirin_desktop_reduce_motion` exports it.
- **Binding.** `NativeApplication.reduceMotion()` takes no arguments and returns
  an `int`, so the call passes nothing by value.
- **Host.** `DesktopApplicationPlatform` compares the value with
  `runtime.reducedMotion` before driving each turn. It writes both only when the
  value changes.

## Checks and results

Run on macOS 15 (Darwin 24.1) arm64 with JDK 25.0.4.1, from task branch
`reduced-motion-20261031`.

- **`MotionPreferenceSuite` (desktop): 1 new test.** The native read agrees
  with `defaults read com.apple.universalaccess reduceMotion`. On this machine
  that key does not exist, which means off, and the read returned off. If the
  defaults domain is unreadable, the test is skipped with that reason.
- **`MotionPresentationSuite` passes unchanged.**
- **Full suites:** `desktopJVM/test` 114 passed. Only desktop code changed, so `testJVM` and `testNative` are those of the base commit (439 each).

## Limits

Nobody has switched the setting on a real machine. The test cannot do it
without changing the user's accessibility settings, so the "on" path has not
been checked end to end. A change made while the host is parked takes effect
only after its next wake, because the host polls and does not observe
`NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification`. The bounded
workbench and editor hosts do not follow the setting.
