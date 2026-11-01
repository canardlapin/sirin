# Native last-window close and persistent workspace lifetime

This continues the reported disagreement in
`bd-01M6AGHZWYQXQ9H63EPW16C77E`. The prior
[real-window evidence](2026-10-31-workbench-window-lifecycle.md) preserves the
failure: native close returned normally from `PersistentWorkbench.main`, while
Command-W/Command-0 cycles retained the application.

## Repair and scope

The pinned SDL header documents that `SDL_HINT_QUIT_ON_LAST_WINDOW_CLOSE`
defaults to `1`: a last-window close request also emits `SDL_EVENT_QUIT`.
The native application shim did not select a different policy for an explicit
application owner. Its application loop correctly treated the resulting Quit
event as an application quit request, which could retire the workspace.

The shim now selects the close policy at every context initialization:

- An explicit application sets the hint to `0`, leaving window close requests
  separate from application Quit. The application owns its workspace until an
  explicit quit is accepted.
- A legacy implicit standalone-window context sets it to `1`, retaining its
  close-to-quit behavior.

The override priority prevents an environment hint or earlier context from
changing the host's documented lifetime contract. Failure to install the policy
fails initialization before SDL ownership is created. Explicit Quit events
continue through the existing application guard and worker-retirement path.

This implements the existing
[native application design](../design/decisions/2026-10-30-native-application.md).
It changes no transaction, scope, identity, scheduler, or command semantics.
There is no new ABI, callback, or FFM/Scala Native binding entry point.

## Verification boundary

The existing `ApplicationHostProbe` pushes an `SDL_EVENT_WINDOW_CLOSE_REQUESTED`
directly into SDL's queue. That bypasses the native close-button path which
produces the additional Quit event, so its earlier pass did not cover this bug.
The packaged native close button is the required regression check.

Shared `DocumentWorkspaceSuite` checks already cover draft and save ownership
across zero windows and application-quit consent. Both JVM and Scala Native
remain required. These model checks do not substitute for the native button
check, nor establish a qualified Scala Native application provider.

## Results

The fresh package ran from Finder, with the same isolated test-document
directory as the original acceptance attempt. No diagnostic launcher was used.

1. Clicking the native last-window close control removed the window. Process
   `38509` remained alive, and Command-0 recreated the workbench controls.
2. The saved document was opened and its body changed to
   `Unsaved draft survives the last native window close.` using native AX
   SetValue. Native close displayed the workspace's dirty-close decision.
3. Confirming closed the view. Command-0 reopened the same unsaved body and the
   dirty marker, without a process restart.
4. Closing the dirty view again and pressing Command-Q with no windows open
   restored a decision view stating that one unsaved draft would be discarded.
   Keep editing retained the draft. Save then disabled both save buttons.
5. Command-Q exited. A process check confirmed that PID `38509` no longer
   existed. The saved file contained the exact draft body.

The test document was copied to `close-policy-saved-draft.sirin-doc` before
restoring the original synthetic fixture to its previously recorded SHA-256.
This preserves both the earlier persistence evidence and the new saved result.

| Check | Result |
|---|---|
| `testJVM` | 447 passed |
| `testNative` | 447 passed |
| `desktopJVM/test` | 124 passed |
| `probeParagraph/test` | 40 passed |
| `examples/compile` | Passed |
| `desktopJVM/desktopWorkbenchPackage` | Passed |
| Native AccessKit lifecycle/protocol checks | Passed |
| Unmodified package portability and library loading | Passed |
| Configured isolated package signature | Verified |

The live test also found a usability defect: dirty-close and dirty-quit
decisions appear below the viewport and require scrolling to discover. That
does not invalidate the lifecycle result, but it prevents calling the workbench
fully usable. Visible decision dialogs, editor layout, native accessibility,
physical input and the wider application and Native-delivery gates remain open.

Artifacts are under `/private/tmp/sirin-workbench-acceptance-20261031/`:

| Artifact | SHA-256 |
|---|---|
| `close-policy-full.log` | `8aaddd666c54c36716a9dad46e57a26f1baf659320191a55b6389040aa77464c` |
| `close-policy-accesskit.log` | `5413682e5642b96c1396eb683d3f982670a287ed11e9ce9d54733d812c7623cc` |
| `close-policy-portable.json` | `e9c5d6a633f98e6822980766d6ecf9901d72fac6258bec8cbe253208ace26c30` |
| `close-policy-launch-receipt.json` | `28cc3eabc010629170baa45d9f7c6c41d5359d23f369bfb0a058d56eba07b0d3` |
| `close-policy-ui-receipt.json` | `5d30c81cffca9bc0c7a6aee2d1a3f8f43417f9eb9e3eab90b9ec4c4a755aa7d7` |
| `close-policy-saved-draft.sirin-doc` | `45a21d49724f94c4ff90791bf374378045d6887764d5ebbf9976675f1c16d98e` |
