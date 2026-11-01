# Text transport during popup focus restoration

A popup can leave a non-text button focused while its dismissal waits for a new
frame. On macOS, stopping SDL text input during that interval loses typed text
before it reaches the owner: key-down events arrive, but no text events are
produced. Reconstructing text from key codes would break keyboard layouts and
IME input, and keeping text input active on every button would expose an input
method where no editing target is intended. Both alternatives are set aside.

After a committed popup closure, the controller may identify the pending restore
target from the last accepted interaction geometry, using the same Previous/To/
anchor/Nowhere policy as actual restoration. It excludes closed popup layers,
retired bindings and modal-excluded targets. It does not predict through a newly
opened modal whose geometry has not been accepted. A surviving text target can
rearm the native text transport using that accepted caret area while the new
frame is pending. This is transport preparation only: logical focus, reference
signals and accessibility focus still change on accepted presentation.

Text and composition events always require accepted geometry. All key-down
events during the closing-popup focus transition also wait. The existing bounded
owner queue retains these events, and delivery revalidates the focused element
after the new presentation. No key is translated into text by the host. A vetoed
dismissal, Nowhere policy, non-text restore target or focus already moved outside
the closed popup does not arm an unrelated editor. Window activation and native
text-client ownership rules remain unchanged.

The preparatory rectangle is the last accepted geometry, not a claim that a
pending layout has been presented. If the surviving target's layout changes, the
next accepted frame refreshes its native text area before queued edits are
delivered. Real IME remains a separate acceptance gate.
