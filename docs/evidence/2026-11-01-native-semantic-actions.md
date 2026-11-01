# Additional native semantic actions

Scope: owned worktree `/private/tmp/sirin-workbench-20261031`, parent `50af801`.
Mote: `bd-01M6AJSBAHZK8PH6J9A0H8J01F`. This implements additional action
transport; relationships, collection positions, semantic-part focus and actual
VoiceOver acceptance remain open.

## Result and boundary

The shared pointer-only accessibility ABI is now version 3. A single additional
entry point copies each part's action-kind and name-length arrays plus one
concatenated UTF-8 buffer. The native side validates the whole bounded batch
before replacing the pending node's declarations. Native callbacks enqueue
copied facts and never call Scala. Both FFM and Scala Native bind the same header.

Increment and decrement map to AccessKit's native actions. Dismissal is an
AccessKit custom action labelled `Dismiss`, because pinned AccessKit C 0.23.1
has no general dismiss action. A user-declared `Custom("Dismiss")` remains a
different action. Named actions retain their exact UTF-8 names. Malformed
encoding, embedded NUL, names over 256 bytes, duplicate declarations and batches
over 32 entries are refused. Existing node, text-field and queue limits remain.

Custom IDs remain stable for a declaration on the same semantic part across
reordering. Retired IDs are never reused. A request that still names a removed
action cannot invoke its replacement. Queued requests retain the presentation
revision and copied name they observed. The owner checks revision, current
presentation, enabled state, exact part and the declared action before using
the existing transactional element handler.

## Verification

The first focused controller run failed the new disabled-target assertion:
the generalized owner path lacked its own enabled-state check. The added guard
repaired that case; the original failing log remains preserved. The controller
tests also exercise two parts within one public element, wrong-part and
undeclared requests, stale revision, retirement and transactional rollback of
a refusing action. A custom action named `Dismiss` reaches a different part
from the built-in dismiss action. Codec checks cover deterministic declaration
order, exact Unicode, malformed names and storage bounds.

The native-only protocol builds against the actual pinned AccessKit library,
without an application window or managed callback. It verifies advertised
actions in the native tree, increment/decrement/dismiss/custom queue round trips,
copied names, a custom request from a pthread, stable IDs across reordering,
removed-ID rejection, queued old-revision retention, disabled/retired targets,
ID exhaustion and atomic refusal of malformed batches. Existing text, selection,
checkbox, dialog, lifecycle and queue tests also run unchanged.

Native queue checks and owner dispatch checks are separate layers of evidence.
They do not establish invocation through VoiceOver. The workbench does not gain
an extra user-facing command solely to demonstrate this transport.

Raw logs and receipts are under `/private/tmp/sirin-semantic-actions-20261101`.
`artifacts.json` hashes the receipts and logs; its SHA-256 is
`31ed0e67be72c55de19cd1bf5d5e6ebe94f6935cb62245037b25f041f4485e44`.

## Final checks and package

Required shared suites passed: 449 JVM and 449 Native tests. The first full
desktop run passed 138 tests, but the final batch ABI/encoding changes were
completed during its later commands. Its desktop result is therefore not the
final-source qualification. After freezing all edited implementation and test
file hashes in `final-source-receipt.json`, the full desktop suite passed again
with 138 tests, the Native ABI probe reached all eight entry points, examples
compiled and the workbench packaged successfully. Both sbt commands exited 0
under JDK 25. Paragraph probes were not rerun for this action-transport change.
The final source hashes were checked again after all runs.

The unconfigured app passed native dependency, font, signature and headless
library-load checks. The previous installed acceptance image is preserved in
`/private/tmp/sirin-workbench-acceptance-20261031/semantic-actions-previous-package`.
The fresh image uses the same approved isolated documents directory and normal
production launcher; no test fixture is installed. The Native ABI executable
also passed against its exact packaged library, whose path was independently
observed in the dynamic-loader log.

Packaged SHA-256 values:

| File | SHA-256 |
|---|---|
| `sirin.jar` | `196657724de6beb7d09726e9474ea1eda087ca18b09dedc393399f1fc8c7946a` |
| `libsirin_desktop.dylib` | `98559c20cee9f1a8eec9ddd9635c6a0c1b0a8c6395736e14fa0d7d42f0fb3ee7` |
| `SirinWorkbench.cfg` | `7b91379ed2d47aa5c154ec6fe76334f982f0d92b3b6f34f856052ff94ea4c3a5` |

Through Computer Use, Finder launched the fresh package. Clicking the
`Acceptance Alpha` accessibility button opened the exact stored body, with Saved
state and Save disabled. Screenshot inspection confirmed the editor and controls
rendered. Import opened a second window with its source field focused; closing
it restored the document window and exact body. Command-Q exited, verified from
Finder and the application inventory without reopening the app. Independent
hashes show the fixture document is unchanged. This is a package smoke check
of existing actions and window handoff, not a real-client test of the additional
actions. No system preferences changed.

The shared checkout's tracked diff still matches its preserved SHA-256
`1bfa8a28e367ba1c946dcaf2e7febba495c95b8c52127c2379ac590320dfa588`.
Production Scala Native pins and kernel policies are unchanged.
