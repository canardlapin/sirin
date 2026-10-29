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
- Use SDL text input/clipboard and AccessKit with native-owned macOS semantics snapshots.
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

Continuation integrates the unfinished editor source from08d9837 with accepted
camera13030fe in `/private/tmp/sirin-editor-20261029`, on
`codex/editable-workbench-20261029`. Preserve the prior editor worktree and its
failed IME/focus controls. The distinct local app identity is SirinEdit /
`io.github.canardlapin.sirin.edit.integration`, avoiding the earlier running
Sirin/SirinEditor packages. Verify actual native typing, selection, clipboard,
undo and AX queries/actions through computer use in addition to in-process
controller tests. A direct NSTextInputClient composition control is reported as
that specific seam; it does not substitute for an actual Japanese/Chinese input
method or VoiceOver user session. If those cannot execute, retain explicit open
qualification rather than mark them passed. This slice supplies the editable
form; the complete asynchronous search/save/reorder/import workbench remains its
own subsequent product integration gate.

Owner-selected AccessKit replacement: use AccessKit C0.23.1 and its macOS
subclassing adapter, replacing the inherited direct Cocoa AX classes. Keep
activation synchronous from native copies, enqueue revision-tagged actions, and
raise notifications after releasing snapshot locks. Combine wgpu, ICU4X and
AccessKit in one pinned `sirin-native` static library per VISION6.1. Rich text
ranges are omitted for fields containing a grapheme longer than255 UTF-8 bytes,
while value/focus/set-value remain available; never split a grapheme silently.

Native validation continuation: retain a complete frame after transient surface
presentation refusal and retry at a bounded16ms wait while repaint is pending.
Do not sleep indefinitely waiting for another OS event before first presentation
or while queued editing awaits its new geometry. This is the development host
loop only; kernel scheduling and transaction policies remain unchanged.

The ordinary host regression initially hit its fixed120s deadline at step0,
before any accepted presentation. Apply the same foreground activation and
bounded retry for a complete pending frame to DesktopWorkbench, preserving its
frozen fixture, input timestamp boundary and sample counts; keep action50/100ms
and scroll100/200ms p95/p99 targets.
Keep the failed receipt and measure the corrected package as a successor.

Native validation completes the bounded implementation only. Actual Japanese/
Chinese input methods, VoiceOver and true pointer drag remain open in Mote
`bd-01M64NKHTDEJ7WZGQBTA6SMF23`. Native Shift-click selection and the direct
NSTextInputClient preedit/commit seam are reported separately.
