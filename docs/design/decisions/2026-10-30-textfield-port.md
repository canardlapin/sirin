# TextField on the public seam

VISION §5 requires "TextField must use only public APIs". TextField now meets
it. `TextField.Description` is a `View.Proxy` whose body is a public
`Component` holding a public `Element`, in `core/src/main/scala/sirin/TextFieldElement.scala`.
Its behavior comes from the text channel, the editing services, focus events and
text-range semantics, the same APIs open to application controls. The host's
private editor is gone: `Content.Editable`, the capture and layout paths for
editors, the editor branch of `Interaction`, the editor payload of frame
requests, and the legacy key, pointer, IME and accessibility branches of
`EditorController`.

This changes VISION's owner-authorized editable text slice in one respect. The
host no longer owns selection, provisional composition and undo state. The
mounted field owns them, as described below. Everything else in that slice
holds: bounded edits, normalization, transactional publication, retained
layouts driving every geometric query, and native transport.

## Ownership and publication

The field's component scope owns one cell holding the draft: the editing model,
which is the former `TextEditing` (now in core and private to Sirin), plus any
refusal notice and the bound cell the draft belongs to. Every edit writes the
bound value and the draft in the same transaction, so a refusal rolls back
both. The field depends on its bound cell, so binding a different cell mounts a
new field even when the text is equal, as before. The draft is ignored once the
bound text differs from it, which ends the composition and history after an
external replacement.

`onCommit` runs inside `txn.attempt`. A refusal there, or an edit over the
field's limit, keeps the draft, shows the bounded reason as the field's error,
cancels any composition, and reports the refusal through
`TextEditingServices.refused`. A refused blur still vetoes the focus move,
through `FocusEvent.Lost`.

## Geometry and presentation

Measure, paint, semantics and text input share one geometry built from the
field's declared text requests. That geometry covers the label, the error, the
placeholder, the text area, and the scroll that keeps the caret in view. Key and
pointer handlers rebuild the same geometry from the routed frame, using
`presentedSize` and lookup-only `presented`, so navigation and hit testing use
exactly what was painted. Multiline text with hard breaks uses the document
layout from the text-control seam.

Two small public additions make this possible:

- `Element.semantics(state, size, text)` lets semantics depend on presented
  text. The field's text origin moves with its scroll and labels.
- `TextInputRequest.composing` lets the host end the platform's composition when
  a presented control has dropped its own, as happens after external
  replacement.

The base drawing the renderer caches carries no focus. `FramePayload.textFocus`
names the focused text control, so its caret and selection are painted in the
base drawing, and its cache key changes only when text focus moves.

## Host feedback and workbench

A handled key or text event that reports no refusal clears the host's last
notice, as a successful legacy edit did. The editor workbench no longer mirrors
private undo availability into its Undo and Redo buttons. They send the field's
own undo keys, and its scripted run checks instead that external restoration
retires history. The render-recovery test no longer serializes the refused
request. Element frames hold code, as the Button port already found for the
recovery frame, and the test still reproduces the refusal directly.

## Scope

No transaction, identity, lifetime, scheduling or command-policy semantics
change. The C shim and native transports are unchanged.
