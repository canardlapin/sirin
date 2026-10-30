# Application menus and keyboard routing checkpoint

This implements `Windows.menuBar` and active-window menus through the existing
Command and reconciliation contracts. Application menus survive zero windows;
shared titles merge; stable bindings expose enabled/running state. The headless
routing law is IME/focused-control keys, innermost local binding, window command,
then application command. A disabled match records the command refusal and stops
routing. Captures retain one argument binding per description, with repeated
capture tested against live graph counts.

The JVM provider now presents Cocoa menu trees instead of refusing nonempty
menus. It privately builds a bounded tree before publication, displays key
equivalents, and queues only immutable revision/item facts from native callbacks.
Window changes, replacement bindings, enablement changes and retired owners
invalidate old facts. Zero-window equivalents remain native actions because SDL
has no window to receive them. No callback enters Scala from a foreign thread.

Validation: **363 JVM + 363 Native + 61 desktop cases pass**, plus authoring
compilation. The full shared/desktop baseline passed 363/363/60. A final desktop
successor normalizes Cocoa event deletion/traversal characters to the documented
menu equivalents and makes character chords use the unmodified layout consistently
across Cocoa/SDL. The complete desktop suite then passed 61 cases, including a
pure native normalization check across FFM without an application. Full shared
sources did not change. Objective-C/C compile with Wall/Wextra/Werror. Tests also
cover command argument enablement, running work after window close, local/window/
application precedence, two-field Tab during composition, hostile equality,
merged namespaces, row/depth/text/chord budgets, and retired presentation IDs.

Preserved negatives: the initial fixture lacked the runtime's dependent owner
type and constructed Work inside UiTxn; the next fixture used undeclared reads;
a local test-class runtime check failed Werror; the first full run incorrectly
expected a caught hostile write to leave the read phase usable. The corrected
assertion requires the specified read-only abort and unchanged Cell state. No
production guard, test limit or acceptance threshold was loosened. Raw failures
and their fixture sources remain under history/.

**No GUI has been launched for this checkpoint.** Actual Cocoa menu presentation,
keyboard layout behavior, IME candidate operation, VoiceOver and native client
acceptance remain open. These headless/offscreen checks do not qualify a Scala
Native application provider or a shipping toolkit. The original 600-second GC
and event/ticker qualification and all six alpha deliverables remain required.
Dialogs, public programmatic quit services, complete persistent workbench,
controls, diagram editor, inspector/starter and independent comparison remain
unfinished.

The preceding f908f6b host checkpoint has all nine CI jobs successful; its exact
result is recorded in previous-host-ci.json. That is separate from CI for this
menu successor. The packaged f908f6b SirinAlpha.app remains byte-for-byte unchanged
while the launch confirmation is pending. This successor has not replaced that
candidate and has not been packaged locally.

Run `python3 -B docs/evidence/application-alpha/menus/verify.py --sources` to
reconcile receipts, frozen sources, case totals and current implementation hashes.
