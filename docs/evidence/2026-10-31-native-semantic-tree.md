# Native semantic-tree transport

Scope: the owned workbench branch on parent `80b07f4`. This implements
[the semantic-tree design](../design/decisions/2026-10-31-native-semantic-tree.md)
for the JVM macOS host and supplies a matching Scala Native ABI probe.

## What the checks cover

- The native protocol test builds an actual AccessKit tree containing a modal
  Dialog, explanatory Label, checked Checkbox and disabled Button. It checks
  parent-child IDs, focus, checked/unchecked transitions, queued Activate,
  refusal of unsupported SetValue, nonfocusable labels and disabled targets.
  Missing/cyclic parent chains refuse publication while preserving the prior
  snapshot. Total-node and legacy-field bounds have explicit overflow checks.
- Existing native UTF-16 selection, text-run geometry, reverse selection,
  oversized-grapheme fallback, foreign-thread queue, overflow and retired-target
  checks remain intact. No native callback enters Scala.
- Rendered workbench tests at 640x400 and 1000x720 verify the native snapshot
  contains the dialog and explanatory text, parents its children, omits the
  modal background, retires dialog IDs on dismissal, and restores background
  controls. These are in addition to the earlier focus and persistence checks.
- Checkbox tests check native role/state and stable identity, reject stale
  actions and activate through the current presented element handler.
- Diagram tests publish all 512 visible custom nodes with distinct native and
  part IDs. A separate current-part activation test selects the intended node
  and preserves its identity. This does not prove every semantic relationship
  or action is supported.
- The JVM FFM check and Scala Native executable both call all seven functions
  in `desktop/native/accessibility.h`, verify ABI version 2 and exercise
  invalid-owner handling without a window. This proves link/reachability and
  the rejection contract; it does not prove a Native application provider.

## Failures and fixes

The first new native hierarchy test aborted inside AccessKit. The implementation
was attaching later children to a parent after transferring that parent's
ownership into the tree update. It now completes all parent links before any
node ownership transfer. The exact failed run is retained as
`semantics-native-3.log`; its corrected successor is `semantics-native-4.log`.

The first Scala Native ABI run reached the library but failed an assertion:
the test expected -1 for a null-owner poll, while the established desktop shim
returns 0 (no queued action). The probe now checks that existing contract;
the shim behavior was preserved. Both attempts are retained.

Generic semantics need their own count budget: the previous 64-field protocol
was designed for controls with potentially large text-run payloads. Applying
that count to every new diagram part would regress the supported 512-node
scene. The generic tree is bounded at 1024 nodes. The old field capacity stays
64, and text/span, action-queue and renderer budgets are unchanged.

## Remaining qualification

Native semantic relationships, position-in-set and actions beyond
focus/activate/text editing remain unimplemented. Real VoiceOver reading,
navigation and activation are separate from debug-tree or AX automation.
No result here resolves the Native provider, P0.6 GC, actual IME, physical
trackpad, large-window or matched ScalaFX acceptance requirements.

## Local artifact hashes

Artifacts are retained under `/private/tmp/sirin-workbench-acceptance-20261031`.

| Artifact | SHA-256 |
|---|---|
| `semantics-native-3.log` | `5f34b3e941d924a16959f5dbbd25954c6f35aba79719af2a2300dd16ec676283` |
| `semantics-native-4.log` | `d04a42e06a6beabe8d7e7360ad68ce9474849bfcdd8c9357a9a024e2664e92d1` |
| `semantics-native-final.log` | `d04a42e06a6beabe8d7e7360ad68ce9474849bfcdd8c9357a9a024e2664e92d1` |
| `semantics-focused-1.log` | `c0fe14a09279aa55d403a5bbf7dbd7a985830f6195602b582186622da914036d` |
| `semantics-focused-2.log` | `9101cf08072abfc3cba6f0df19afde66c5c7a01a86c585a6b11f75fc21402c05` |
| `semantics-focused-3.log` | `86e07b16e3c477c822e91039e3afcb80e44ee19fc11a1e7342121d696bb05e74` |

## Full checks and fresh package

| Check | Result |
|---|---|
| `testJVM` | 447 passed |
| `testNative` | 447 passed |
| `desktopJVM/test` | 127 passed |
| `probeParagraph/test` | 40 passed |
| Examples | Compiled |
| Native protocol | Passed |
| Scala Native accessibility ABI executable | Passed |
| Workbench package | Built |
| Unconfigured package portability/library load | Passed |
| Isolated configured package signature | Verified |

The package launched from Finder at the approved test path. macOS AX exposed
Documents and Search ready labels. Native button activation opened Acceptance
Alpha, and native SetValue replaced its body with
`Native semantic tree: retain this draft.`. Command-W produced a native
`dialog Close this view?` with its title, explanatory text, Keep editing and
Close view as children. The background document controls were absent from the
AX tree. Keep editing activated through the new mapping and restored them.
Native Save wrote the exact draft body, verified by decoding the document;
Save then became disabled and the Saved label appeared. Clean Command-Q exited
with `procNotFound` on the following AX observation.

The saved result is archived and the original fixture restored to its recorded
SHA-256. No real checkbox specimen or VoiceOver session was run in this package
check. Permission to temporarily enable VoiceOver and its caption panel was
requested and remains pending. No macOS accessibility setting was changed.

| Artifact | SHA-256 |
|---|---|
| `semantics-full.log` | `d0149190cc5b07df3562efb0ebd332924e8a015c79a4dad311c68daf701afb56` |
| `semantics-portable.json` | `09c9b016312bdc22b9217319b5d34f791e71130e18e0a4ecf51d04f47db7ecdb` |
| `semantics-launch-receipt.json` | `2210dcb2a4eb02e39141e431034bb156f4ff33c943a7c69b88944d696089c86e` |
| `semantics-ui-receipt.json` | `536d318982f22030633e66756c555984d4419dab5a344e89b46602357b1590a4` |
| `semantics-saved-draft.sirin-doc` | `a3dbf22cff335e3a0c853bb8febcc3bd5f757fc3e39dcc003332ee3fdfb970f6` |
