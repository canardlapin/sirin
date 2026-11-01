# Native semantic relationships and text-field errors

This change transports the existing public semantic relationships through the
macOS accessibility bridge. The JVM and Scala Native bindings reach the same
pointer-only ABI v4. It does not qualify a Native application provider, actual
VoiceOver behavior or semantic-part keyboard focus.

## Behavior checked

The owner reads an immutable snapshot of live committed reference identities.
The window address book resolves relationships after assigning its native IDs.
Forward targets work, while foreign references with coincident numeric IDs,
other-window references, hidden/clipped targets and modal-excluded background
controls do not produce links. Explicit custom parts must be unique within the
referenced subtree; an unspecified part selects its first exposed semantic node.
An active descendant must be below its source in the native hierarchy.

The native protocol copies the five metadata facts, supports forward references,
publishes label/description/active-descendant IDs and collection position/size,
and refuses invalid positions, missing targets and unrelated active descendants
without replacing the accepted tree. Empty direct text does not override a
relationship. Label nodes carry their text as native values. These properties
were inspected in the actual AccessKit tree-update debug representation; this
is transport evidence, not an assistive client's reading result.

TextField uses a field-owned public reference to describe its painted error
Label part. Its direct description and invalid state remain. A rendered test
checks error appearance, clearing and reappearance: the main field retains its
ID, the error's retired ID is not reused, and clearing restores the hint and
removes the relationship. The existing identity/reorder, refusal, recovery and
retained-draft assertions remain in the shared suites.

## Verification history

The first focused compile found a missing reference argument in the field's
editing handler. After repair, the engine identity test and 18 focused desktop
tests passed. Full checks then found three failures in engine test helpers and
one in the workbench helper: their patterns expected the old unwrapped field
snapshot. Their exact lookups now include the new reference wrapper. No
assertion or test was removed. Both failed full logs are retained.

The sources were frozen before the final complete run and their hashes checked
again after packaging and UI exercise. With JDK 25 explicitly selected, the
command was `sbt testJVM testNative desktopJVM/test probeParagraph/test
probeAccessibilityNative/run examples/compile desktopJVM/desktopWorkbenchPackage`.
It exited 0, with 450 JVM, 450 Scala Native, 142 desktop and 40 paragraph tests
passing. Examples compiled and the workbench packaged. The native protocol
command `bash desktop/tools/test-accesskit.sh` also exited 0 on the final sources.

The unconfigured image passed `desktop/tools/portable-image.py --load-library`,
including native dependency closure, bundled fonts and deep/strict signing.
After installing the isolated-document configuration and signing again, the
Native ABI probe exited 0 against the exact packaged dylib. The dynamic-loader
log confirms that library's path; all nine ABI v4 entry points were reached.

## Real-window check

The fresh normal-production package at
`/private/tmp/sirin-workbench-acceptance-20261031/SirinWorkbench.app` was launched
through Finder under the user's existing approval. It uses only the isolated
`documents` directory beside it. The previous package was archived in
`semantic-relationships-previous-package`; no fixture launcher was installed.

The saved Acceptance Alpha document opened with its exact title/body and Saved
status. Clearing Title showed a red border and the painted message "A title is
required." The native accessibility snapshot exposed that message both as the
field's Help and as a separate text entry. Save remained disabled. Restoring
the original title removed the error entry and Help and enabled Save. This
observes the public client result; the exact relationship IDs are established
by the rendered and native protocol tests, not by this AX snapshot.

Command-Q showed the dirty-draft modal with background controls excluded.
Discard and quit exited the acceptance app. The document's bytes and the
directory inventory were unchanged, with SHA-256
`1e717858dda45fc34d230f0c58294cd96d9a286e0ed0a8776bdd51fa86a37d28`.
The unrelated Sirin application was left running.

An AX-index click on Title did not establish keyboard focus; a coordinate click
did. This repeats the earlier focus observation and is retained as an open
limitation. The checks use synthetic input and do not qualify VoiceOver,
physical pointer use or semantic-part focus.

## Artifacts and limits

Raw logs and receipts are kept outside Git in
`/private/tmp/sirin-semantic-relationships-20261101`. The artifact manifest
`artifacts.json` has SHA-256
`749d0ed6367aaf8f1fde813dbe5bb2090ede94673aad7164f510b7b645d9d13f`.
It covers original failed logs, the successful full run and native protocol,
source hashes, portable-image checks, exact packaged ABI loader output, package
hashes, UI observations and before/after document receipts.
The installed JAR has SHA-256
`ee57c40abd7f42178d1460931c42f8dda6b90b1cc73d997429238e0450a771e9`;
the dylib has SHA-256
`e077512235ba84718f0e85f69682f23790b6cb9bb133dd8343cfc1de83df9076`.
The design decisions are in
`docs/design/decisions/2026-11-01-native-semantic-relationships.md`.
Kernel transaction, attachment, identity, lifetime and focus policies are
unchanged. Real VoiceOver, real IME, physical trackpad/drag, the Native provider,
P0.6 qualification and the delivery-order decision remain open.
