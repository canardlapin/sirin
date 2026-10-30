# Text control seam prerequisites, 2026-10-30

## Claim

Public elements now have what the built-in TextField needs beyond text input:

- focus events that can veto a move away;
- hard-line text documents through ordinary text requests;
- the presented size and refusal feedback in editing services;
- declared editing keys and chords that take precedence over shortcuts.

The design is in
[the decision](../design/decisions/2026-10-30-text-control-seam.md).
TextField itself is still the legacy host editor.

## Checks and results

These were run on macOS arm64 with JDK 25.0.4.1, from task branch
`textfield-port-20261030`. Four new tests in
`desktop/src/test/scala/sirin/desktop/TextInputSeamSuite.scala` cover this:

- **Focus.** A Tab move delivers `Lost` to the old element and then `Gained`
  to the new one. A refused `Lost` keeps focus in place, counts a refusal and
  delivers no `Gained`. Deactivation delivers `Lost` without moving focus.
- **Documents.** An element requests `á\r\nשלום\n\nx`. Measure, paint,
  the prepared inventory and text semantics all see one composed layout. CRLF
  has no interior caret, and later paragraphs sit on later lines.
- **Declared keys.** A NoteField that declares Primary+C and its editing keys
  reserves exactly those, ahead of shortcuts. Primary+Shift+C and Enter stay
  with application routing.
- **Services.** A key handler receives its presented size of 240 × 40. A
  refusal it reports is counted, with its notice, only when its transaction
  commits. A refused transaction shows the generic refusal notice instead.

Suite results:

- `desktopJVM/test`: 109 passed, including the unchanged `EditorControllerSuite`
  and `ButtonSeamSuite`.
- Full `sbt testJVM`: 422 passed.
- Full `sbt testNative`: 422 passed.
- `examples/compile` passed.

## Limits

No native window, real IME or VoiceOver session was run.
