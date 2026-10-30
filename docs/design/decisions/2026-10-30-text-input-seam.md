# Public text input, composition and text-range semantics

VISION §5 requires TextField to use only public APIs. Before this change, IME
composition, the clipboard, segmentation and accessible text ranges reached only
the host's private editor. This decision adds those capabilities to the public
Element seam, so any custom element can be a text control. TextField itself
moves onto the seam in the next tranche.

## The text channel

`TextInputEvent` is the third input channel, separate from physical and logical
keys (VISION §6). It has five cases: committed text, a composition update,
cancellation of a composition, and two assistive requests, select a range and
replace the value. Every offset is UTF-16. The host converts the platform's
codepoint indices and normalizes line endings before delivery, so no element
sees platform conventions.

An element opts in with `Element.textInput(state, size, text)`. It runs on the
semantics state and returns a `TextInputRequest` carrying the caret rectangle.
The host enables platform text input only while the focused element declares
one, and it places the IME candidate window at that caret, clipped to the
element. The callback receives the lookup-only prepared text, so the caret comes
from presented geometry and nothing is shaped.

## Key precedence

The VISION order is unchanged: IME composition, then focused editing keys, then
local shortcuts, then window chords. The host tracks composition from the events
it delivers. While an element is composing, keys go to the IME. Escape abandons
the composition, and the element receives `CancelComposition`. A focused text
element sees every key in its `key` handler first. Printable keys that it
declines stay reserved for the text channel. Command chords that it declines
still reach application routing.

Composition ends with a cancellation when focus moves, when the window
deactivates, when presentation is suspended, or when a refused event leaves
native marked text behind. A removed element only has its native marked text
cleared, because nothing remains to notify.

## Editing services

Text controls need platform facilities that a pure element cannot supply.
These are grapheme and word segmentation, the clipboard, composition
cancellation, and the layout presented for a text request. `TextEditingServices`
provides them. The `text` handler receives them, and so do new two-argument
`key` and `pointer` overloads, which default to the existing forms.

Clipboard writes and composition cancellation are staged. They take effect only
if the handler's transaction commits, so a refused edit leaves the clipboard and
the IME untouched. `presented` only looks up the inventory of the frame that the
event was routed against. Paths without a text platform use
`TextEditingServices.unavailable`, which refuses segmentation and copying
instead of guessing.

## Text-range semantics

`SemanticNode.text` takes a `TextSemantics`. It names a text request, the
request's origin in the element, a directional selection, and whether the text
is multiline and editable. The host prepares that request from the same
inventory as paint, so assistive geometry always matches what is drawn. It
publishes the first text node of each element through the existing native field
transport, with grapheme and caret spans. Assistive focus, selection and value
requests are routed to that element. A selection outside the exposed text is
refused before dispatch, and stale revisions are refused as before.

## Scope and limits

No transaction, identity, lifetime, scheduling or command-policy semantics
change. Each request still uses one transaction. The C shim and the native field
transport are unchanged. Native accessibility shows one text node per element.
Only arbitrary semantic parts need more transport. Real macOS input methods and
VoiceOver have not been checked against this path; that remains with the
existing qualification issue. `demo.text.NoteField` is a specimen, not a
supported control.
