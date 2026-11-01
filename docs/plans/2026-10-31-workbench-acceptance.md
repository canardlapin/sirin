# Usable persistent workbench on macOS/JVM, with Scala Native retained

Owner goal: a complete, usable persistent workbench on macOS/JVM, without
forgetting Scala Native. This continues step 4 of the public-toolkit execution
sequence. Passing a control suite or building an app image is an intermediate
result. The goal stays open until the required application evidence exists.

## Required outcomes and evidence

| Outcome | Evidence needed | Current position |
|---|---|---|
| Persistent workflow | Fresh package: create, search, select, validate, edit, save while continuing edits, reopen exact content, retain drafts across filtering/reordering/pane moves | Exact create/edit/save/relaunch round trip checked in a real window; broader workflow acceptance remains open |
| Failure and lifecycle behavior | Import review/cancel receipts, external-change conflict, save-copy/reload, dirty close/quit, zero-window reopen, no stale publication after retirement | Real conflict/no-overwrite, save-copy, reload consent, partial-import receipts, zero-window draft retention and Quit consent checked; live cancellation remains open |
| Coherent input | Popup dismissal/restoration/nesting, keyboard traversal, trackpad scroll, pointer drag, resize, multiple windows and recovery | Two popup regressions repaired on main with expanded shared and desktop checks; physical input acceptance remains open |
| Native accessibility | Checkbox role/checked state, dialog containers and usable custom semantics through the pointer-only bridge; native action round trips; actual VoiceOver reading/navigation | Dialog/label/checkbox/custom-part transport and shared ABI checks pass; fresh-package modal AX tree verified; actual VoiceOver and remaining semantic relationships are open |
| Text input | Actual IME composition/commit/cancel, focus transitions, editing and clipboard in the packaged workbench | Controller checks exist; actual IME sessions remain unqualified |
| Visual usability | Review legible text, spacing, focus/error/loading states, light/dark appearance, display scale, reduced motion and usable large-window behavior | Foreground Finder launch renders; default-size editor and visible decisions checked; large-window acceptance remains open |
| Public authoring | A separate public consumer implements one real feature using the documented API, builds/packages from a clean checkout, and completes its workflow | Independent review and starter workflow remain incomplete |
| Matched comparison | One explicit equivalent workflow in Sirin and ScalaFX, with source, behavior and measured presentation path stated | Not yet performed |

## Scala Native is a required parallel track

- Run `testJVM` and `testNative` for shared changes. Put shared input laws and
  regression cases in the shared host suite wherever possible.
- Keep FFM and Scala Native bindings on the same pointer-only shim. Native
  accessibility additions require ABI/binding parity checks; no Scala callback
  enters from a foreign thread.
- Revalidate the exact Linux Native reconciliation failure and its existing
  negative evidence before declaring it resolved. Later green runs alone are
  insufficient.
- Continue a bounded causal investigation of P0.6 from its preserved
  standalone trap/conditional controls. Keep runtime pins and thresholds
  unchanged without an explicit design decision. The original idle-host
  600-second event/50 ms qualification remains required.
- State the application-provider gap explicitly. Shared Native test passes
  do not establish Native desktop delivery. Obtain the explicit delivery-order
  decision required by VISION before Alpha6; do not silently substitute JVM
  delivery for the Native target.

## Order of work

1. Repair the already reported popup modality and nested-shortcut violations,
   retain the original failing evidence, and rerun the complete required checks.
2. Complete accessibility transport for the implemented controls, with native
   round trips and shared binding verification.
3. Build a fresh workbench package against an isolated document directory;
   exercise the application workflows and repair the failures they expose.
4. Complete real-client, visual and independent-authoring evidence. Preserve
   missing or unavailable manual results as incomplete.
5. Carry the Native investigation and explicit delivery decision alongside
   application progress; close neither from unrelated passing tests.

Popup prerequisite repairs landed on main at `1a3ca02`. The first real-window
checks verified an exact persistence round trip and exposed repeated AccessKit
class setup on window reopening. The native regression and packaged Command-W/Command-0 reopening pass with a
process-lifetime guard. The separately reported native close-button exit was
repaired on continuation by selecting SDL's application-owned close policy.
Real-window checks now retain drafts across zero windows and restore dirty-Quit
consent. Decisions now use a visible modal popup, and the editor uses the available
pane width and height. Fresh-package dirty-close/quit, focus restoration,
docking, zero-window draft retention and exact saving were checked; see
[usability evidence](../evidence/2026-10-31-workbench-visible-decisions.md).
Native Dialog/label/Checkbox and custom-part transport now has native and
rendered checks plus a fresh-package modal AX observation. JVM FFM and a Native
probe reach the same v2 ABI. Actual VoiceOver permission is pending; the broader
acceptance outcomes and remaining semantic relationships stay open.
Packaged recovery checks now preserve exact files across external-change
conflicts, copy/reload and partial import. Import review validity, local errors,
readable receipts and stable navigation were repaired through public APIs.
The attempted live cancellation completed too quickly to interrupt and remains
unqualified. Keyboard input loss after switching back from Import to main was
traced to native text-client ownership and repaired in the shared shim; both
hidden-window and packaged keyboard handoffs now pass. A Native executable also
checks the existing text/focus ABI in the exact packaged library. Closing an
active window now activates the most recently active survivor while retaining
its control focus. Sidebar titles ellipsize within fixed rows and preserve full
accessible names. An Arabic-title native text-publication crash was reproduced
and repaired; clean-package open/edit/save and close-and-type checks pass.
Repeated handoffs exposed a separate GPU frame coverage/generation/inventory
rejection, tracked in `bd-01M6APTQYS91VRFD96KXG58PMK`. Retry retained state, but
its cause remains unresolved. That failure and broader acceptance gates prevent
full usability acceptance. See
[window and title evidence](../evidence/2026-11-01-workbench-window-rows.md).
See [recovery evidence](../evidence/2026-10-31-workbench-recovery.md) and
[window-input evidence](../evidence/2026-11-01-workbench-window-input.md).

Use the authoritative Mote store in the original checkout. The current worktree
is `/private/tmp/sirin-workbench-20261031`. The first popup-repair worktree was
retired after landing.
Tranche issue: `bd-01M6AF7EAPRRC63YH4TQ3MF1SR`. Original alpha obligations
remain tracked separately; this plan does not close any of them by inference.
