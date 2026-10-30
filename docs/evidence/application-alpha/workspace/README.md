# Persistent public workspace implementation checkpoint

This successor integrates the real persistent workbench with the public application
platform. Search/select/edit/validation, cached drafts through reorder/filter/outlet
moves, separate document/import views, revision-associated saves, reload/copy,
external-change notices, dirty-close and application quit now compose through
public APIs. File import freezes IDs/stamps, refuses overwrite, retains committed
partial files on cancellation and reports received receipts separately from writes.
The application guard works with zero windows. Ordered owner input prevents a
window-close fact from overtaking already queued text or geometry-dependent input.

Full checks: **384 JVM + 384 Native + 79 desktop**, plus examples compilation and
`desktopJVM/desktopWorkbenchPackage`. `full-checks-1/` contains commands, exact
sources, source hashes and full logs. The public workbench integration test uses
real worker threads, filesystem adapters, capture/layout/native fonts/Unicode,
offscreen rendering, controller input and window shortcut routing to save and
reopen exact Unicode text. It opens no native window. Shared Native checks do not
establish a Scala Native UI provider.

## Renderer failure and exact control

The first whole workspace exceeded the unchanged 500M per-sample work budget.
`contracts-3/` retains that failure and sources. Host traversal had nested the
already-intersected Placed.clip at every ancestor. The successor clips each node's
own paint/overlay and traverses children outside that redundant scope; rectangle
intersection and painter order are preserved. No renderer limits were increased.
`old-clips-control/` freezes four trusted generated FrameRequest payloads and exact
old/successor Layout sources. The exact old source fails replay; the successor
passes the same four payloads (`clip-replay-successor/`). These serialized fixtures
are internal trusted test data, not an external file import format.

Other failed compilation/fixture attempts remain under history/contracts-1; full
stdout is absent for the initial enum syntax failure and its observation says so.
Later passes do not erase those failures. `workspace.png` is the final full-check
headless snapshot, not a native screenshot or visual approval; it visibly retains
layout/viewport and emoji coverage shortcomings.

## Package and acceptance boundary

`workbench-portable.json` proves native dependency closure, strict deep signature,
packaged fonts and library loading without UI. `launch-candidate.json` freezes the
specific separate SirinWorkbench.app image. No native app was launched for this
receipt. Earlier SirinAlpha/SirinEdit images are preserved. The preceding public
main commit has all nine CI jobs passing (`document-main-ci.json`); this is not CI
for the uncommitted successor.

Native menus, keyboard/focus/scroll, actual IME candidate interaction, VoiceOver,
modality/dialogs, drag, lifecycle and visual acceptance remain unqualified. The
GPU frame cap stays 1,048,576 physical pixels; larger surfaces need bounded
streaming. Emoji coverage and field/diagnostic limits remain toolkit work.
Public diagram editor, bounded inspector, clean-checkout/independent authoring,
matched ScalaFX comparison, long GC/event qualification and the unresolved Linux
Native failure cause remain open. No numbered alpha deliverable is closed.

Verify frozen receipts with `python3 -B docs/evidence/application-alpha/workspace/verify.py`.
Use `--sources` to compare current source bytes, and `--candidate` only on the
machine retaining the frozen app image. See docs/guides/persistent-workbench.md
for building and using the development application.
