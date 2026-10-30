# Focus, documents and editing feedback for public text controls

The text input seam (2026-10-30-text-input-seam.md) let a public element
receive text. The built-in TextField needs four more things before it can move
onto that seam. This note adds them. It changes no transaction, identity,
lifetime, scheduling or command-policy semantics.

## Focus events

`ElementHandlers.focus(event)` receives `FocusEvent.Gained` and
`FocusEvent.Lost`. Each runs in its own transaction. The host delivers `Lost`
before a focus move takes effect. A refused `Lost` vetoes the move and is
counted as a refusal, which matches TextField's existing `onBlur` refusal. The
host delivers `Gained` after the move; a refusal there is reported but focus
stays. Window deactivation also delivers `Lost`, which cannot veto
deactivation. Reactivation delivers nothing, so presentation should follow
`ElementFeedback.focused`, not a tally of events. The legacy editor's blur and
element focus share one transition, so a move between a legacy field and an
element is ordered and vetoed the same way.

## Hard-line documents

A `TextRequest` whose content contains hard line breaks now resolves to a
`DocumentTextLayout` when requested through `TextServices` or `PreparedText`.
The layout is composed from that request's prepared paragraphs. Each paragraph
is an ordinary counted entry, so glyph, source and request budgets are
unchanged. The document's own 256-paragraph budget still applies. Paint, hit
testing, carets and text semantics all see the same composed layout. Until now,
only the host's private editor composed documents.

## Editing services

`TextEditingServices.presentedSize` gives the recipient's presented size in the
routed frame. With it, a key handler can rebuild the exact request it painted
and look it up with `presented`. `TextEditingServices.refused(reason)` reports
an edit the control refused on its own, such as an over-limit paste. Like the
clipboard, it is staged: the host counts the refusal and shows the bounded
notice only if the handler's transaction commits. A control that refuses its
whole transaction is reported as before.

## Declared editing keys

`TextInputRequest` lists editing `keys`, reserved under any modifiers, and
exact command `chords`. Both take precedence over application shortcuts, as
VISION's key precedence requires: "Text controls consume only
primary-modifier chords they declare". Printable keys stay reserved for the
text channel. Undeclared chords reach application routing.
