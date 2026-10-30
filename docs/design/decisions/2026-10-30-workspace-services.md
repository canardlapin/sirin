# Application services and persistent workspace integration

The persistent workbench is application code using the existing public reactive,
command, ownership, docking and Window contracts. It does not introduce another
scheduler, identity system or task model. Document scopes outlive views. A bounded
cache owns at most 32 open document models; selection parks inactive retained
panels at an unattached outlet. Search/refresh/reorder update collection summaries,
not drafts or editor identities. Disk reload replaces an editor only after explicit
confirmation and a second revision/instance check after I/O completes.

Confirmations carry the relevant document generation and committed field revisions.
Editing while a prompt is open invalidates its authority: refresh the prompt and
require a new decision. Reload waits for that document's queued saves. A window
close can hide a clean view without disposing its workspace, while dirty close
requires a decision. Application quit must be able to resume after that decision.

Add Application.run((Scope, ApplicationServices) => Windows), preserving the
existing one-argument entry. ApplicationServices.quit is an ordinary scoped
Command. The desktop provider uses the existing owner-checked afterCommit hook
to queue a native quit fact. Refused/failed outer transactions never request quit;
disposing the owner before publication suppresses the callback. Native quit still
uses the same window close decisions; it is not a bypass. Backends without service
support refuse the new entry explicitly before invoking its builder. No existing
kernel transaction or command policy changes.

The first confirmation surface is an explicit decision panel in the workspace.
It is not promoted to native/modal dialog or focus-trap qualification. Native file
panels, popup/modality/focus restoration, actual IME/VoiceOver/client operation and
all six alpha deliverables remain required. Import uses bounded scoped progress,
retains committed partial documents on cancellation, and reports its denominator;
retry does not silently replace an existing document.

Work.map/flatMap/attempt are lazy sequential composition on the same Work ticket,
not new scheduling or ownership. Composition checks Cancellation before the first
step and between steps, and never converts InterruptedException into recoverable
data. attempt exposes NonFatal failures explicitly as Either; no retry/fallback
is added. The same NotGiven[UiTxn] construction guard applies. This lets filesystem
service adapters compose public Work operations without privileged execution.

Windows.onQuitRequest is application-owned and runs once before per-window close
requests, including when there are no windows. KeepOpen publishes confirmation
state without closing any view; Allow then applies each existing window's guarded
close. The guard belongs to the mounted application root and refuses after owner
disposal. This closes the zero-window dirty-draft hole without adding a special
privileged quit bypass or changing individual window-close semantics.

The owner input queue treats a window-close fact as an essential ordered boundary.
Preceding text, composition and geometry-dependent facts publish before the close
handler sees dirty state. A pending close stops further native polling for that
peer until the boundary is consumed; KeepOpen resumes polling. Retirement drops
remaining facts, and the existing 256-fact budget is unchanged.

The first full-size public workspace exposed repeated ancestor clips in host
paint/overlay traversal. Placed.clip already contains the intersection with every
ancestor. Apply that clip to each node's own paint, then visit children outside
that scope. This preserves the same effective rectangles and painter order while
avoiding depth-proportional duplicate clip work. Keep every operation, storage,
intersection and per-sample work budget unchanged; do not shrink the workspace
render fixture or switch it to a different renderer to evade the refusal.

The workbench keeps one main view, up to three document views, and one import
view. Import review/progress has a direct command/shortcut and a separate window;
closing that view leaves the app-owned stream live. Approved application quit
cancels the active import (committed files are retained) and drains existing saves.
Opening a new view while the host drains cancels its earlier quit intent, so it
cannot later exit beneath newly resumed interaction.

Current qualification limits remain explicit: the retained GPU frame budget is
1,048,576 physical pixels (the current 640x400@2x fixture fits), and emoji glyph
coverage is incomplete in the current font stack. Large-surface streaming,
viewport adaptation, scrolling/focus polish, full glyph coverage and native client
operation remain toolkit/graphics/alpha work; do not enlarge budgets or infer
those claims from a 1000x720@1x headless render.


Application-global quit and native menu facts must also wait behind input already
queued on any live peer. Moving the guard after the peer drain alone is insufficient:
a pointer/navigation fact can be waiting for presentation, holding later text.
Keep the quit intent pending until every live owner queue is empty, then run the
application guard and poll native menu actions against that committed state. A
peer likewise postpones accessibility actions behind its own pending input. This
orders already transported facts; it does not claim a cross-source timestamp
ordering between Cocoa callbacks and SDL events that have not been pumped yet.
Repeated quit requests coalesce while waiting, and retirement clears obsolete
input. Existing rendering/wake progress continues so geometry can settle; no
busy wait, input discard, callback thread or kernel policy is introduced.
