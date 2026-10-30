# Public window model and lifecycle checkpoint

The public Window/Windows constructors are implemented using existing keyed
Component/ForEach ownership. Mounted window snapshots expose separate title,
menu description and content roots. Native close facts route by mounted instance,
not source key. Same-key replacement/reorder preserves drafts and control IDs;
source removal or accepted dismissal retires only the window row. Source data,
workspace documents and workspace jobs remain owned independently.

Window.onClose is transactional: refusal or failure rolls back the close and
handler writes. Window.onCloseRequest can return KeepOpen to commit prompt state
without dismissing. A confirmed asynchronous close can remove the source key.
Dismissed keys carry source revisions; later removal events clear them, while
older delayed events cannot reopen a newer closed window. The owned KeyedCell
record store preserves existing read-only key equality checks.

Final checks pass **342 JVM + 342 Native + 50 desktop cases**, plus authoring
compilation. These include 14 window lifecycle cases and one adversarial key case
on each shared platform. Commands, exact source hashes and raw logs are under
validation/. The desktop checks are headless regressions; no app UI was launched.

Two draft regressions were observed and fixed. Closing via onClose(app.dispose())
originally failed with DisposedScope because default dismissal wrote afterward.
The fix prepares key/revision first and skips dismissal if the window owner was
retired by the handler. An armed key equality callback originally ran with UI
write authority while updating a plain dismissal Map. The fix uses the existing
guarded KeyedCell operations. Both negative runs, intermediate source patches,
preliminary passing runs and a retained fixture compilation failure are in
history/. These are distinct development generations, not repeated final tests.

source.patch reconstructs the final tested source from b0facfb; the final
tracked source hashes in validation/result.json must match. The pre-application
close patch contains 13 lifecycle tests; use the final 14-case lifecycle test
file with that old source to replay the additional shutdown regression. The
before-key-fix patch includes its failing adversarial test. No assertions or
existing randomized-property limits were weakened.

Run python3 -B docs/evidence/application-alpha/windows/verify.py for frozen
receipt integrity and denominators. Add --sources to also require this checkout
to match the exact tested source hashes. Integrity verification is not execution.
Re-run the recorded sbt commands in a fresh checkout with pinned native artifacts.

This is **not completion of the application platform or alpha**. Application.run
still has no backend; the current C transport still permits one native window.
Application-owned SDL lifetime/global event routing, native menus/shortcuts,
dialogs/confirmation wiring, the persistent visible workbench, remaining controls,
diagram editor, inspector, independent authoring and platform acceptance remain.
Actual IME/VoiceOver/drag and the original GC event gate are unqualified here.
The separate Linux replay at b0facfb passed all three attempts but did not resolve
the original main CI ClassCastException.
