# Application and window menus, shortcuts and callback ownership

Additive implementation of VISION sections 4, 5.2, 5.3 and ruling R11. The full
six-part alpha programme remains required; no transaction or command policy is
replaced here. Existing frozen app launch candidates remain untouched while their
action-time approval is pending.

Windows.menuBar declares application-owned menus. They are metadata under the
existing static root reconciliation, not a second window/command lifetime. Their
command signals remain rooted with zero open windows. Window menus contribute
active-window commands. Application and window top-level menus with equal titles
merge in order; window entries follow application entries. All controls dispatch
the same Command instances and recheck current enablement inside UiTxn.

MenuItem and view Shortcut bindings retain one unit-argument binding per immutable
description. Reconciliation declares enabled/running dependencies; capture reads
settled values and retains failed enablement as disabled diagnostic data. Menus
have at most 512 nodes per bar, depth 32, with existing shared capture text/node
budgets; a presented merged menu is also bounded by 512. Local keyboard bindings
are bounded by 256 per window. Native facts are bounded by a 64-entry FIFO.

Matching keyboard input first goes to IME composition and focused control editing
or activation keys. Then matching local onKey/shortcut bindings bubble from the
focused node outward, followed by window and application command chords. A
matching disabled shortcut is consumed and dispatch records the shared command
refusal; it cannot fall through to an unrelated command. Native menu shortcuts
are displayed but matching user key equivalents are offered to the Scala-created
UI owner instead of invoking Cocoa actions ahead of the IME/editor. Standard
macOS Quit/Hide keys remain native defaults. User bindings can override them;
when a routed local binding is inapplicable, the UI owner supplies the standard
fallback rather than losing the key.

Native Cocoa menu actions copy only presentation revision and item ID into native
owned facts and wake SDL. Every item carries the revision of its own immutable
presentation, rather than reading a newer revision at click time. Replaced menu
items, retired window bindings and stale queued facts cannot activate a different
command at a reused position. Managed dispatch validates the current revision,
active-window instance and binding identity before UiTxn; no foreign callback
executes Scala. Menus are built privately then published on main; closing the
application detaches its menu tree and restores the prior native menu.

Actual Cocoa menu/key-equivalent operation, focus/IME interaction, dialogs and
full client acceptance need their own native launch receipts. Shared tests and
headless native compilation do not establish those results.

Logical character chords use the current layout's unmodified key, with modifier
bits represented separately and character case canonicalized. The SDL adapter
therefore translates the physical scancode with no modifiers or SDL's optional
Latin/French substitutions; text still arrives only on the committed-text channel.
Cocoa menu equivalents differ from key-event characters for Backspace and Forward
Delete: normalize event Delete (0x7f) to the Backspace equivalent (0x08), and the
Forward Delete function character (0xf728) to the Delete equivalent (0x7f).
Shift-Tab and keypad Enter normalize to the public Tab/Enter keys. The pure native
normalization is checked across FFM without creating an application. See Apple's
[keyEquivalent contract](https://developer.apple.com/documentation/appkit/nsmenuitem/keyequivalent)
and the pinned SDL3 SDL_keyboard.h / SDL_hints.h contracts. Actual layout and IME
operation remains part of native acceptance.
