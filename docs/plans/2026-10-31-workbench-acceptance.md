# Usable persistent workbench on macOS/JVM, with Scala Native retained

Owner goal: a complete, usable persistent workbench on macOS/JVM, without
forgetting Scala Native. This continues step 4 of the public-toolkit execution
sequence. Passing a control suite or building an app image is an intermediate
result. The goal stays open until the required application evidence exists.

## Required outcomes and evidence

| Outcome | Evidence needed | Current position |
|---|---|---|
| Persistent workflow | Fresh package: create, search, select, validate, edit, save while continuing edits, reopen exact content, retain drafts across filtering/reordering/pane moves | Exact create/edit/save/relaunch and queued-save/newer-draft behavior checked in real windows, the latter with controlled storage latency; broader acceptance remains open |
| Failure and lifecycle behavior | Import review/cancel receipts, external-change conflict, save-copy/reload, dirty close/quit, zero-window reopen, no stale publication after retirement | Real conflict/no-overwrite, save-copy, reload consent, partial-import receipts, zero-window draft retention and Quit consent checked; live cancellation checked in a real window with controlled storage latency |
| Coherent input | Popup dismissal/restoration/nesting, keyboard traversal, trackpad scroll, pointer drag, resize, multiple windows and recovery | Two popup regressions repaired on main with expanded shared and desktop checks; physical input acceptance remains open |
| Native accessibility | Checkbox role/checked state, dialog containers and usable custom semantics through the pointer-only bridge; native action round trips; actual VoiceOver reading/navigation | Roles, actions, relationships, collection positions and exact part focus have transport/owner checks; fresh-package modal AX tree verified; actual VoiceOver is open |
| Text input | Actual IME composition/commit/cancel, focus transitions, editing and clipboard in the packaged workbench | Controller checks exist; actual IME sessions remain unqualified |
| Visual usability | Review legible text, spacing, focus/error/loading states, light/dark appearance, display scale, reduced motion and usable large-window behavior | Foreground Finder launch renders; default and larger windows, visible decisions, resize draft retention and exact saving checked; smooth live resize and remaining appearance/display checks stay open |
| Public authoring | A separate public consumer implements one real feature using the documented API, builds/packages from a clean checkout, and completes its workflow | ReviewPad persists notes and review status through public APIs; clean framework/SDK and independent consumer builds pass; real-window approval and workflow acceptance remain pending |
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
  unchanged without an explicit design decision. The November 1 activation
  diagnostic observes the restore tail and detects synthetic ownership failures;
  three short saved-mask trials and one four-allocator follow-up complete without
  observed overlap or lost signal-mask bits. Original controls still abort.
  These private results require independent review and do not adopt a runtime.
  The original idle-host 600-second event/50 ms qualification remains required.
  See [activation evidence](../evidence/2026-11-01-native-activation.md).
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
probe reach the same ABI, including bounded batches of increment, decrement,
dismiss and named custom actions. Native and owner tests cover exact part/name
dispatch, stable action IDs and rejection after disablement or retirement. See
[action evidence](../evidence/2026-11-01-native-semantic-actions.md).
ABI v4 adds label, description and active-descendant relationships plus collection
positions. Resolution uses live reference identity and the exposed window tree;
text fields describe their painted error label and clear the link with the error.
See [relationship evidence](../evidence/2026-11-01-native-semantic-relationships.md).
An in-process Cocoa client now checks actual platform objects in a hidden window.
It exposed missing Help for linked-only descriptions; the repaired bridge retains
the relationship and supplies its textual fallback. Native Focus requests queue
correctly and accepted focus follows the adapter's active-view state. These checks
do not substitute for window activation or VoiceOver; the generic tool-click
observation is still unqualified. See
[Cocoa client evidence](../evidence/2026-11-01-cocoa-accessibility-client.md).
Exact semantic-part focus now flows through public intents, accepted geometry,
reference signals and native owner dispatch. Tests cover rollback, clipping,
modality, removal, veto and exact popup restoration; a hidden Cocoa client
confirms non-first graphic-part focus. Part-only changes republish native focus
without invalidating the base drawing cache. See
[part-focus evidence](../evidence/2026-11-01-semantic-part-focus.md).
Bounded owner-thread runtime inspection now copies scope/signal identities,
dependencies, revisions, committed change causes, command refusals and work flags.
A workbench regression diagnoses a disabled save through its empty-title input
without advancing work or changing the graph. The optional Developer inspector
now provides capture, search, paging, dependency/cause navigation and copied
render/cache receipts through an ordinary tool window. Shared lifecycle tests
and offscreen CPU/GPU checks pass; actual packaged-window operation, complete
per-phase invalidation and latency qualification remain open. See
[runtime inspection](../tooling/runtime-inspection.md).
Actual VoiceOver permission is pending; the broader acceptance outcomes stay open.
The final part-focus package exposed a further coherent-input defect: immediate
typing after Escape dismisses the dirty-quit popup can lose its prefix, while
typing after observing restored focus succeeds. A controlled render delay traced
the failure to native text input remaining disabled before restored focus is
presented. The repair prepares native transport for the surviving restore field
and queues delivery until accepted presentation. Controller regressions pass;
two repaired diagnostic-window trials with a 200 ms render delay now retain
all immediately typed characters. Production-window verification remains pending.
The defect stays open. See
[text-handoff evidence](../evidence/2026-11-01-popup-text-handoff.md).
The independent ReviewPad consumer now builds and packages from a committed
external copy against a clean binary SDK. Four storage regressions pass,
including a reproduced and repaired malformed-text load failure. Its separate
window launch approval remains pending. A further approved SirinWorkbench window
check passed title validation, dirty-quit Escape draft retention and discard
without changing saved bytes. See
[public consumer evidence](../evidence/2026-11-01-public-starter.md).
Packaged recovery checks now preserve exact files across external-change
conflicts, copy/reload and partial import. Import review validity, local errors,
readable receipts and stable navigation were repaired through public APIs.
The natural-speed cancellation attempts completed too quickly to interrupt. A
later test-only storage wrapper made the in-flight boundary observable in the
real UI: Cancel retained three committed files with two delivered receipts and
no later publication. Controlled queued saves also preserved newer unsaved
edits, and the restored production package reopened the exact final save. See
[controlled-work evidence](../evidence/2026-11-01-workbench-controlled-work.md). Keyboard input loss after switching back from Import to main was
traced to native text-client ownership and repaired in the shared shim; both
hidden-window and packaged keyboard handoffs now pass. A Native executable also
checks the existing text/focus ABI in the exact packaged library. Closing an
active window now activates the most recently active survivor while retaining
its control focus. Sidebar titles ellipsize within fixed rows and preserve full
accessible names. An Arabic-title native text-publication crash was reproduced
and repaired; clean-package open/edit/save and close-and-type checks pass.
Repeated handoffs exposed a process-wide three-frame cap inherited from the
single-window renderer. Packet inventories now preserve the three-frame bound
per device/generation under a bounded process total, without increasing texture
memory. A failing two-device regression, concurrent native accounting, the Native
batch path and the exact packaged handoff sequence verify the repair. See
[renderer inventory evidence](../evidence/2026-11-01-window-frame-inventory.md).
Application viewport limits now allow 4096 by 3200 physical pixels with bounded
cache and native output accounting. Real packaged zoom, shrink/grow with unsaved
text, two enlarged windows, editing, saving and exact relaunch passed. A transient
stretched frame during zoom is recorded, so smooth live resize remains open.
See [larger-window evidence](../evidence/2026-11-01-application-viewport.md).
Broader acceptance gates still prevent full usability acceptance. See
[window and title evidence](../evidence/2026-11-01-workbench-window-rows.md).
See [recovery evidence](../evidence/2026-10-31-workbench-recovery.md) and
[window-input evidence](../evidence/2026-11-01-workbench-window-input.md).

The November 1 Native continuation verified the frozen October 29 evidence and
ran one unchanged idle preflight. All six samples failed the idle requirement,
so the original long event gate remains unrun. The saved-mask source review
requires checking ownership across the entire redirected trampoline lifetime;
zero nested signal handlers alone does not establish it. Runtime defaults remain
unchanged. See [Native continuation evidence](../evidence/2026-11-01-native-continuation.md).

Use the authoritative Mote store in the original checkout. The current worktree
is `/private/tmp/sirin-workbench-20261031`. The first popup-repair worktree was
retired after landing.
Tranche issue: `bd-01M6AF7EAPRRC63YH4TQ3MF1SR`. Original alpha obligations
remain tracked separately; this plan does not close any of them by inference.
