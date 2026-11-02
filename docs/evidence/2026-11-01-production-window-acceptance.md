# Production appearance and popup input in a real window

The owner approved the signed appearance workbench and temporary system
appearance changes. The package at
`/private/tmp/sirin-system-colors-20261101/SirinWorkbench.app` uses only its
sibling `documents` directory. It has no diagnostic render delay and leaves
the inspector disabled. Its application source is `1a9baa3`.

## Results

- The empty document directory opened in Light appearance. New created a
  document, and coordinate clicks plus keyboard input edited its title/body.
  A generic accessibility click on Title did not focus it; the coordinate
  click did. This does not establish what accessibility action the tool sent.
- With the editor focused and dirty, Command-Q opened the native dialog tree
  and visible quit popup. Escape immediately followed by
  `typeText("AFTER-ESCAPE-0123456789")` in one computer-use call retained every
  character and restored editor focus.
- System Settings initially selected Light. Switching to Dark changed the
  workbench palette without changing the draft. The first observation showed
  a dark native title bar with the previous light content; the next showed
  the settled dark content. No switch-latency measurement is claimed.
- Typing ` DARK-RETAINED` reached the same editor without clicking it again.
  A second dirty-Quit/Escape/immediate-input trial appended
  ` DARK-ESCAPE-0123456789` intact in Dark appearance. The popup text, buttons
  and focused Keep editing border were visible in that palette.
- Restoring Light preserved the draft and focus; ` LIGHT-RETAINED` reached
  the same editor. Emptying Title showed the visible required-title message,
  native field Help and disabled Save. Restoring the title cleared the error.
- Command-S saved the document. Quit and relaunch of the same package, then
  selecting the document, restored the exact title and body with Saved status.
- A third trial repeated the original title-field failure precisely: replace
  Title with `Probe `, Command-Q, observe the dialog, then Escape immediately
  followed by `typeText("abcdefghi")` in one call. The field and window title
  became `Probe abcdefghi`. The synthetic title draft was discarded through
  the quit popup. Later process inspection found no appearance-workbench
  process, and the saved bytes remained unchanged.

Some immediate AX/screenshot observations caught input before its final
characters had been delivered. Later observations and the independently decoded
saved file contain the entire input. These partial observations are preserved;
they are not final missing-character results. The tests use automated native
pointer/key input, not a physical keyboard or trackpad.

System Settings was verified back at Light, its original value. No other system
preference was changed. Actual VoiceOver reading/navigation, IME, display scale,
reduced motion and owner visual approval remain unqualified.

## Exact persistence and package identity

The sole document is `a82cc383-cc74-48dd-8ea5-3ef918830c05.sirin-doc`, 185 bytes.
An independent Python decoder checked the `SIRINDOC` header, version 1,
big-endian lengths, strict UTF-8, exact identity/title/body and end of file.
Title is `Appearance acceptance`; body is exactly:

```text
Production appearance draft. AFTER-ESCAPE-0123456789 DARK-RETAINED DARK-ESCAPE-0123456789 LIGHT-RETAINED
```

Document SHA-256:
`0ea628f462a73927a0e2018b4882ad27ae003564b402920c2363002f86dbd7d0`.
The package was checked against its frozen receipt:

| File | SHA-256 |
|---|---|
| `sirin.jar` | `1115cac9e38dcf7a0cd1272a4852223416c155080851df9ccf7a5915f529b8b4` |
| `libsirin_desktop.dylib` | `08805d438211ff41d6c5af8da9625186ee0ed5582c67e97e43f9825670aeeb63` |
| `SirinWorkbench.cfg` | `d2463fbc4b65201202229cac0e776ee97a323dbd0fc4e4228c9933f6a8550985` |

The required `sbt testJVM testNative` run exited 0, with 476 tests passing on
each platform. No implementation or test was changed in this window check.
This pass does not explain the earlier Native runner startup timeout recorded
in the [appearance implementation evidence](2026-11-01-system-colors.md).

## Other approved windows and computer-use boundary

The owner separately approved the prepared ReviewPad, inspector and ScalaFX
packages. Their deep/strict signatures passed before launch. ReviewPad first
showed its controls; the following batched editing call reported `App quit`.
Its isolated note file remained absent. Relaunch showed a black window through
several observations, then its controls appeared. Subsequent title input
advanced only from `I` to `In` to `Ind` across observations. A read-only Java
thread dump found the main thread in `NativeApplication.waitEvent` and the
render worker waiting for work. This does not identify the cause or distinguish
application delay from tool delivery/observation delay. No crash report was
found in the inspected user diagnostic-report directory.

The older inspector package opened its Developer menu and a window named
Sirin Inspector, but the captured content was black. ScalaFX launch then
returned Computer Use `timeoutReached`; subsequent process inspection found
no ScalaFX process. Tool wall times are not application startup or input
latency measurements. These attempts establish neither workflow acceptance nor
a matched comparison. ReviewPad and the inspector were still running at the
last read-only process observation; their preserved test state must not be
mistaken for a completed or cleaned-up session.

Following the owner's instruction to continue engineering if computer control
fails, work switched to current-SDK consumer preparation. The original packages
and observations remain intact. No fallback UI automation was used.

Raw screenshots, AX text, exact document copy, decoder receipt, regression log
and the diagnostic thread dump are under
`/private/tmp/sirin-system-colors-window-20261101`. ReviewPad's partial-title
capture is under `/private/tmp/sirin-reviewpad-window-20261101`. Their manifests
contain 21 and 3 files, with respective SHA-256 values
`7387275d69e2143bd912473a0840fbcba47ff913559357a5898129ae8dc95956` and
`1b0ae364aff214e6daa82f596f06307af2038594296c9ac4aa2f9a96e41f6e98`.
The earlier frozen appearance implementation manifest is unchanged.

## Current-SDK preparation after the tool timeout

An SDK was exported from the default `1a9baa3` package after verifying that its
JAR and native-library hashes equal the tested appearance image. The exporter
recorded the then-clean source checkout. An initial export from the isolated
acceptance image was correctly refused by the portable-image check because
its launcher contains an absolute test-document path; that failed log is kept.
The default image passed native closure, fonts, signature and library-only
loading checks. No validation was weakened.

Eight tracked starter files were copied to a new external consumer under
`/private/tmp/sirin-consumer-refresh-20261101`. With only the new binary SDK,
`sbt clean test packageApp 'show Compile/fullClasspath'` passes all four
storage tests. Its classpath contains consumer classes, the SDK and Scala
runtime JARs. Source hashes remain unchanged; its application JAR is identical
to the earlier consumer. Only the framework SDK changes.

New isolated candidates are `reviewpad/SirinReviewPad.app` and
`inspector/SirinWorkbench.app` beneath that artifact root. Both have unique
bundle identifiers, test-file paths beneath their own directories and valid
deep/strict signatures. Neither was launched. This is reproducible preparation,
not evidence that an SDK update resolves the observed failures. The six-file
receipt manifest has SHA-256
`b2ada3d26d64490371a187c397bdd7c83b603280fd18838024907c91be7c658b`;
it references the SDK inventory and exact package JAR/config/library hashes.

At 01:17:44 UTC on November 2 (November 1 local time), a two-second `top`
interval reported 53.88% idle CPU, below the unchanged Native gate's 80%
requirement. This was an availability observation, not the six-sample preflight.
Neither that preflight nor the long Native qualification was launched.
