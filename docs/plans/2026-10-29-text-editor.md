# Editable text, native input and accessibility — 2026-10-29

Authorized by owner after available-host workbench acceptance08d9837. Implement
on codex/text-editor-20261029, preserving existing graphics/kernel policies.

- Implement existing public TextField.bind(Cell[String]) and fluent label,
  placeholder, error, multiline, onCommit/onBlur and decorations. Stable mounted
  control identity owns host editor state; capture carries only immutable values.
  Cell updates use existing UiTxn and commit/rollback rules. External text changes
  cancel preedit, reset stale history and clamp selection to valid boundaries.
- UTF-16 public positions remain grapheme safe. Segmentation delegates to the
  pinned Unicode service. Shaped layout supplies visual bidi/wrap navigation,
  hit-testing, selection unions, caret and accessible bounds; no second shaping.
  Hold essential editor events in order while their presented geometry is stale;
  bound the queue explicitly and refuse overflow, never drop committed text.
- Directional selection, Shift navigation, visual arrows, word/Home/End movement,
  deletion, select-all, clipboard cut/copy/paste and bounded undo/redo. Typing may
  coalesce until a selection, command or composition boundary. Field budget16k
  UTF-16 units/256 paragraphs, history64 entries/2MiB; overflow refuses the edit without changing
  Cell/history. Multiline wraps and keeps the caret visible through a viewport.
- Input normalizes CRLF/CR to LF. Pasted tabs expand to four spaces in multiline
  fields and a space in single-line fields; Tab remains focus traversal.
- The macOS editor can add the installed Arial Unicode static face for CJK.
  Record its SHA in evidence; do not redistribute the system font or alter the
  frozen workbench font set. Other font discovery/color emoji remain separate.
- Physical/logical keys remain separate from SDL committed text and preedit.
  Composition is provisional, drawn and underlined without writing the bound Cell.
  Commit is one undoable edit; Escape/blur/removal/external replacement cancel it.
  Feed the displayed caret rectangle to SDL's platform IME. Consume editor keys
  before window shortcuts; let the platform IME handle its own composition keys.
- Use SDL text input/clipboard and native-owned macOS accessibility snapshots.
  Native AX queries never upcall Scala. AX focus/value/selection/press requests
  enter a bounded native queue with target identity and snapshot revision and are
  revalidated by the owner. Publish values, selection and shaped geometry together
  after presentation; invalid/stale targets cannot mutate state. Native children
  retire safely on close. Clipboard is user-command-only, not background polling.
- Deliver an actual editor specimen using public TextField APIs, keyboard/pointer
  selection, wrapped multilingual text and native semantics. Keep the existing
  frozen workbench fixture reachable unchanged. Tests cover Unicode/bidi, undo,
  rollback, replacement/disposal, input priority, composition/cancellation, native
  ownership and AX actions. Run required JVM/Native aliases, text/native/desktop
  cases, packaged scenario, actual OS typing/selection/clipboard and AX checks.

The user's quiet-precondition waiver persists. No latency threshold, graphics
budget or foreign-thread rule changes. IME/AX implementation and tested platform
paths do not establish every OS input method or screen-reader combination.
