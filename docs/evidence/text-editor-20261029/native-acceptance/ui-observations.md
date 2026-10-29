# Native observations after owner launch approval

The owner approved the specific local SirinEdit launch with "proceed" after the
62d3519 implementation receipt. Computer use launched only the isolated
`/private/tmp/sirin-editor-20261029/desktop/target/package-editor/SirinEdit.app`.
All OS keyboard, pointer and accessibility interactions below used `cua_repl`.
The source worktree and older direct Cocoa app were not substituted.

Initial runs exposed a complete AccessKit tree after activation, but a transient
presentation refusal could leave the editor waiting indefinitely for another
OS event. The preserved traces show generation1 delivered, one failed attempt,
then no retry; a later run also left selection geometry stale. The corrected
editor retains the complete frame and retries on a bounded16ms wait while a
repaint is pending. It also requests normal foreground activation for interactive
startup. Traces retain failed attempts as well as their later accepted frames.
The original failed runs are not counted as successful qualification.

In the retry-enabled candidate the following native attributes and visible
outputs were inspected after the matching presentation:

- Window `Sirin Editor`; Title is a settable text field with help `Name this
  document`; Document is a settable text entry area; Undo/Redo are initially
  disabled and Restore draft is an accessible button.
- Native pointer focus plus Cmd-A and typed `AccessKit title` changed both the
  field's AX value and its visible text. Undo yielded `Untitled` (also reported
  as selected text), and redo restored `AccessKit title`.
- Direct AX set-value changed Title to `Native value action`. The native action
  trace records kind3 accepted. AX selection of `value` returned that exact
  selected-text attribute; kind2 was accepted.
- Native pointer focus and Cmd-A followed by the computer-use paste operation
  changed Document to the exact text below. The paste tool restores the prior
  system clipboard. The visible screen and AX value both matched the text.

```text
Native clipboard café
שלום مرحبا 日本語
á 😀
```

- AX selection of `日本語` returned that exact selected text within the mixed
  direction document. Shift-click selected `Native clipboard ca`, demonstrating
  native pointer range selection on retained geometry.
- Accessible Restore draft generated kind4 accepted and restored the exact
  original three-line document in the AX tree. This exposed a stale Undo-button
  indicator after external replacement; final source synchronizes history
  indicators after controller capture, and updates the last editor for AX focus.
  The final packaged scenario independently asserts cleared history and disabled
  Undo/Redo after external reset.
- Native close removed the candidate process. No SirinEdit crash report appeared.
  Initial close observation timed out after the process had already closed; it
  is not interpreted as a close failure.

The CUA drag attempt delivered down/up at the starting point and no native drag
motion in its trace. It does **not** qualify actual pointer drag. Shift-click is
qualified separately. Element-index clicks on text fields were not reliable in
this automation path; coordinate focus and the explicit AX APIs were used and
recorded. No assertion that every assistive client follows these sequences is made.

Some immediately following AX snapshots still showed the preceding frame.
Observations were repeated after native trace evidence of a completed matching
presentation; stale snapshots are not treated as the new value. This is a
functional check, not a latency measurement. Images were visually inspected in
the computer-use transcript; no native screenshot file was exported into this
receipt. GPU readbacks in the parent receipt are not native screenshots.

Japanese/Chinese text paste is not a real Japanese/Chinese input method.
The packaged scenario's direct real NSTextInputClient mark/commit seam is also
reported separately. VoiceOver and real input-method user sessions remain open.

## Final package refresh

After the complete JVM/Native/desktop matrix and host regression, the final
package was opened again with tracing disabled. Document's AX set-value was
`History reset check`; the rendered field, 19-character counter and enabled Undo
agreed. Subsequent accessible Restore draft activation/read showed the exact
original draft with Undo **disabled**; Redo remained disabled. The corrected
indicator behavior was therefore observed through macOS AX as well as asserted
in the packaged scenario. Native close was then performed. The CUA background
launcher still required foreground title-bar activation before visible output;
no invisible-window drawing claim is made.
