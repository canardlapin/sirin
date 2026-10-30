# Field input limits and recoverable edit feedback

TextFieldView.maxLength(units) declares the maximum committed UTF-16 length
accepted from user input. The default remains 16,384 and values outside 0..16,384
refuse at construction. Decorations forward this setting without losing field
identity, bindings, validation or callbacks. The workbench uses 256 for title and
search, 2,048 for the import directory, and the existing default for body text.
This is an input policy, not another Cell validator: programmatic values are
preserved and display a field error if over the configured limit. The existing
hard host/editor Unicode, paragraph and total capture limits remain unchanged.

Typing, paste, native text/IME commit, undo/redo and accessibility replacement all
publish through EditorController.applyState. An edit over the configured limit
refuses atomically, preserving committed text, selection and undo history. No
substring is silently kept. IME preedit remains provisional under its existing
4,096-unit and total field safety budgets; a refused final commit cancels marked
text on both sides while retaining the original draft/history. Selection and
navigation remain available to recover from programmatically invalid text.

The controller retains a bounded per-mounted-field edit notice. A current notice
is projected into the immutable Content.Editable.error used by layout, painting
and the accepted accessibility snapshot. Geometry readiness compares that same
projected tree. Successful text publication/commit clears the edit notice;
external replacement and field retirement clear it too. Domain validation is
preserved underneath the edit notice. No callback writes application state from
a native thread, no second reactive scheduler is added, and no rejected edit is
published to the application's Cell.

Feedback is at most 512 UTF-16 units and retained only for live editor identities.
Actual VoiceOver announcement and native IME candidate operation still require
client validation; rendering a diagnostic and passing controller tests does not
establish those claims. Document save error messages are likewise bounded for
presentation without changing retained error data or filesystem behavior.


Field captions and error blocks reserve their resolved wrapped text height, with
existing 20-unit minimum spacing, instead of assuming every diagnostic fits one
line. The editor area, painted border and intrinsic field height use those same
metrics. Explicit parent height/viewport constraints still apply and scrolling
remains required when the available space is smaller than the resulting content.
