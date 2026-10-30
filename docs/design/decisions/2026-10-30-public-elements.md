# Public custom elements and layouts

Implement Element and Layout as mounted public descriptions, retaining existing
Component/key/Scope ownership. Declared layout/paint/hit/semantics signals are
settled inputs. Input handlers remain on the UI owner, with instance and presented
geometry checks before transactional dispatch. Removal/disposal rejects stale
handlers and releases capture. No native callback executes Scala.

The engine produces immutable phase snapshots with separate monotonic phase
revisions. Snapshot comparison, user equality and every pure phase execute under
the existing read-only guard, including on Scala-created rendering workers.
Caught UI mutation/read violations still fail the phase. No UiTxn, graph reader,
mutable signal value or native handle is provided to those phases. Application
phase values must obey the existing immutable/revisioned-value contract.

Custom definitions contain phase code and may close over handler-owned objects;
therefore they can remain reachable until in-flight rendering retires, like an
executing Work closure. Their rendering facade exposes only pure phase methods;
UI handlers stay in owner-side bindings. Definitions must not read/write UI state
from rendering. Runtime ownership/phase checks enforce this boundary; no foreign
native thread gains a callback. This extends the first host's closed primitive
snapshot vocabulary without adding a second reactive/lifetime system.

Custom text requests are explicit core values. The graphics bridge retains its
source-compatible TextRequest alias and uses the same prepared-result inventory.
Resolve requests before paint on the native-text owner; paint never shapes as a
fallback. Canvas transforms lower to the existing standalone graphics recording.
No alternate renderer, shaping algorithm or larger rendering budget is introduced.

LayoutPolicy runs read-only over captured state, constrained children and named
slots. Measurements cache by constraints. Duplicate placement, invalid sizes,
unknown slots and invalid/unbounded constraints refuse explicitly. Unplaced
children stay mounted but are absent from paint, hit testing and semantics.
Element hit/semantics use the same accepted layout and captured phase state.

Implement constant/projected Presentation values first; they remain separate from
Signal. Real time-varying animation/layout presentation and global theme/font
invalidation still need their complete implementation/qualification. The diagram
must use real public element/layout/command APIs, with selection, drag, pan/zoom,
undo and bounded dense-scene tests. A compiling descriptor or painted box is not
the finished diagram editor or completion of any six-part alpha requirement.

A committed graph reader belongs to its exact capture phase. A nested custom
phase cannot reuse a captured reader, even on the UI owner, and caught misuse
poisons that phase. Wheel input has an explicit logical event and transactional
handler; accepted local geometry is shared with pointer hit testing. The current
transport supplies vertical wheel deltas and primary/secondary pointer buttons.
The first custom input path preserves FIFO moves; latest-only delivery permits
coalescing but does not require dropping samples. Complete-stream is never
silently coalesced. Pointer queue capacity qualification remains explicit.
