# Persistent workbench: real window checks and AccessKit lifecycle repair

The owner approved launching the isolated workbench package built from
`1a3ca02`. The document directory is
`/private/tmp/sirin-workbench-acceptance-20261031/documents`; the checks use only
synthetic test content. The implementation worktree is
`/private/tmp/sirin-workbench-20261031`.

## Observed application behavior

Computer Use launched the packaged macOS/JVM application. Command-N created a
document. Native accessibility SetValue supplied its body; a coordinate click
focused the title field, and Command-A followed by keyboard typing renamed it.
Save wrote the exact title `Acceptance Alpha` and body
`Workbench acceptance: saved body 2026-10-31.`. After a fresh process launch,
selecting the document exposed both exact values in the native accessibility
tree. The saved file was also checked directly.

Closing the last window with Command-W and reopening with Command-0 crashed
the original process. The macOS crash report points to
`accesskit_macos::patch::add_focus_forwarder_to_window_class`, called from
`sirin_ax_open`. AccessKit installs an Objective-C class method and asserts that
the installation succeeded. Installing it again on the same class aborts.
The bridge previously called it for every new window adapter.

The repaired bridge retains a process-lifetime set of patched window class
names. Main-thread setup installs the hook once per class; closing an adapter
does not remove the class patch or its registration. No public ABI, FFM or
Scala Native extern changes are needed. Callbacks remain native-owned.

## Regression check

`desktop/tools/test-accesskit.sh` now calls the real AccessKit patch for two
NSWindow subclasses, then repeats setup three times for each. It creates no
application or native window. With the old behavior extracted into the helper,
the test exited 134 with `Failed to add method
Sel("accessibilityFocusedUIElement")`. With the process-lifetime guard, the
regression and existing native protocol checks pass.

The original crash and failing regression remain preserved. They are not
replaced by the succeeding test.

The full checks used Temurin JDK 25.0.4.1 and the unchanged Scala Native pin:

| Check | Result |
|---|---|
| `sbt testJVM` | 447 passed |
| `sbt testNative` | 447 passed |
| `sbt desktopJVM/test` | 124 passed |
| `sbt probeParagraph/test` | 40 passed |
| `bash desktop/tools/test-accesskit.sh` | Lifecycle regression and existing protocol checks passed |

The initial combined command completed these checks and then rejected the
mistyped project name `examplesJVM`. The correct project is `examples`; the
command error remains in `accesskit-repair-full.log`. It is not a test failure
or a successful example compilation. A corrected invocation passed
`examples/compile` and `desktopJVM/desktopWorkbenchPackage`. The fresh,
unmodified package passed the portability checker with library loading. The
configured isolated copy passed signature verification.

## Real-window repair check and a separate design disagreement

The repaired package launched through Finder and presented its controls. After
Command-W removed its last window, Command-0 recreated the workbench with its
accessible controls. The former AccessKit abort did not recur.

A later close-button action ended the process without a macOS crash report.
To distinguish orderly exit from an uncaught Java error, a temporary Java
launcher inside the isolated copy redirected Java output to `run.log` and
called the unchanged `PersistentWorkbench.main` with the same document arguments.
Computer Use disallowed Terminal; no Terminal launch was performed. The
diagnostic wrapper did not route input or change the application model.

In that process, two Command-W/Command-0 cycles recreated the workbench.
Clicking the last window's close button then returned normally from the main
method. The log records three retired rendering sessions and normal return,
with no Java exception. The diagnostic jar/configuration were removed from the
test app afterward, restoring the repaired package's recorded jar and config
hashes; the diagnostic source, log and backups remain preserved separately.

This is a distinct contract violation:
[the native application design](../design/decisions/2026-10-30-native-application.md)
requires that closing the last window keep the application context and workspace
jobs alive. The likely cause is SDL's default `SDL_HINT_QUIT_ON_LAST_WINDOW_CLOSE`
behavior: its pinned header documents a quit event on a last-window close request,
and `app_create` does not override that hint. A focused event-level reproduction
is still needed before changing it.

Issue `bd-01M6AGHZWYQXQ9H63EPW16C77E` records the disagreement. AGENTS requires
stopping and reporting a code/document disagreement, so no lifecycle policy
change was made. The AccessKit repair is verified; complete zero-window and
dirty-workspace acceptance remains open.

Successor: the [native close repair](2026-10-31-workbench-native-close.md)
selects SDL's close policy explicitly and verifies clean close, retained drafts,
zero-window Quit consent, and explicit exit in the packaged application.

## Limits and remaining work

- The initial background launch displayed a black window. Subsequent activation
  and input produced the workbench. Thread and heap inspection found a completed
  retained GPU frame and no recorded render failure. A subsequent foreground
  launch through Finder showed the workbench and its controls on the first
  inspection, with no pointer intervention. Command-Q then exited. Delayed
  background presentation remains tracked as `bd-01M6AG50NEW6V9XJ3D9F9DMWA5`;
  neither observation is a measured startup-latency qualification.
- The 640 by 400 logical window requires substantial scrolling to reach the
  editor and save controls. The editor occupies a narrow pane. Visual usability
  and larger-window behavior remain open.
- AX SetValue, pointer focus, keyboard text entry and a disk round trip do not
  establish VoiceOver reading, physical trackpad behavior, or real IME support.
- Checkbox/dialog accessibility, import/conflict/dirty-close workflows,
  independent authoring and the matched comparison remain open.
- Scala Native shared tests and native C protocol tests do not qualify a Scala
  Native desktop application provider or resolve the P0.6 GC investigation.

## Preserved artifacts

Artifacts are under `/private/tmp/sirin-workbench-acceptance-20261031/`.

| Artifact | SHA-256 |
|---|---|
| `original-reopen-crash.ips` | `b9caed32cec973d9db0e47b121b45847304a0fd1cbf1aa139a20e87a6640fd0a` |
| `accesskit-repeat-before.log` | `1fed56201f8e2e44844fc5b96774f4ae1f310d0edb4c71d1aa849e3f6cd0cbd4` |
| `accesskit-repeat-after.log` | `5413682e5642b96c1396eb683d3f982670a287ed11e9ce9d54733d812c7623cc` |
| `blank-window-threads.txt` | `f14ed58db29cd6e664928a31b0e38037ef70631919dab3e2e217edecef78311d` |
| `blank-window-sample.txt` | `1d694ce08353f9e92b18b91cedbc9ce1465e932294d2cdc74436c00e4c0d8db1` |
| `documents/3a07f5e5-a5e1-476e-8f18-a6d37a5d6075.sirin-doc` | `1e717858dda45fc34d230f0c58294cd96d9a286e0ed0a8776bdd51fa86a37d28` |
| `accesskit-repair-full.log` | `0c0f6acb0f22e45baa5191506a994040b837e40ba63318f18b747f30cd6092c9` |
| `accesskit-repair-package.log` | `895883e371693b53dda8a87171d974c2a1b8063271082be15486bedc239ca62b` |
| `accesskit-repair-portable.json` | `439ec635ebb2f58c0ba8ea549192d633d626c332f654b7d0116ea63dc8bf1946` |
| `accesskit-repair-launch-receipt.json` | `5511182c253afd378a6774a497a2c6bd8c008f0c59a35783b1e273030b6d1569` |
| `diagnostic-launcher/run.log` | `e8dc80ff29b354ee0bab003b50faebf2b131663a6ec45d22bae8641147a21b07` |
| `diagnostic-launcher/WorkbenchDiagnostics.java` | `a921c8b49eb1bfc849bd4c761cc344ac509a0a70dbb0e35eff544119ccee3631` |

The original package's launch receipt records its source and binary hashes.
The unmodified package passed the portability checker. The isolated test copy
passed signature verification and library loading; its explicit temporary
document argument intentionally fails the portability check's temporary-path
rule. No portability pass is claimed for that configured copy.
