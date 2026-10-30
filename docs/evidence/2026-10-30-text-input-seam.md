# Public text input seam, 2026-10-30

## Claim

A custom element outside the `sirin` package can be a text control using public
APIs alone. It receives committed text, IME composition and assistive select or
replace requests. It places the IME candidate window, uses platform segmentation
and the clipboard, and exposes text-range semantics. The design is in
[the decision](../design/decisions/2026-10-30-text-input-seam.md). TextField has
not been ported yet. Its port is the next step.

## Checks and results

Run on macOS arm64 with JDK 25.0.4.1 and the pinned Scala and Scala Native
toolchain, from task branch `text-input-seam-20261030`:

- Full `sbt testJVM`: 422 passed.
- Full `sbt testNative`: the same 422 passed.
- `sbt hostJVM/test` and `hostNative/test` passed 54 each, after the final
  cancellation fix.
- `sbt desktopJVM/test`: 106 passed. That includes the eight tests in
  `TextInputSeamSuite` and every existing `EditorControllerSuite` and
  `ButtonSeamSuite` test.
- `sbt examples/compile`: passed.

`TextInputSeamSuite` drives `demo.text.NoteField`, rendering real frames with
HarfBuzz/FreeType and segmenting with ICU4X. It checks the following:

- Commits and codepoint-indexed SDL compositions reach the element as UTF-16
  ranges. Keys and command chords are withheld while a composition is active.
  Escape cancels the composition and clears native marked text.
- Printable keys stay on the text channel; command chords that the element
  declines still reach application routing.
- Copy and paste go through the services, and the IME caret follows presented
  geometry.
- A refused handler transaction discards its staged clipboard write and
  composition cancellation.
- Text semantics name a prepared request. Assistive selection and replacement
  are transactional. Out-of-range and stale requests are refused.
- Pointer hits use presented geometry, and a double-click selects a word.
- Window deactivation and element removal retire the composition. This also
  holds for an element that exposes no text semantics.

## Limits

These are headless host checks with real text and rendering services. No app was
launched. Real macOS input methods, VoiceOver and pointer use have not been
checked against this path; that stays with the existing qualification issue.
Native accessibility shows one text node per element. `NoteField` is a
specimen: single-line, with no undo history and no vertical navigation.
