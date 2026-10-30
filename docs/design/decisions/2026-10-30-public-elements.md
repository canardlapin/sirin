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

The first seam proof includes Button, not the diagram. Button is a public
View.Proxy whose body uses Element with prepared measurement text, semantics,
ElementActivation and ElementFeedback. The renderer definition is stable and
contains no model references; owner-side ElementHandlers are replaced without
remounting the control. Existing command enablement/refusal, focus, pointer
release and Space-release behavior use the shared activation path. An arbitrary
custom element can opt into exactly the same behavior. The legacy host Button
value remains only as a reference/fixture input; ordinary Button no longer emits it.

Element.simple and Element.Simple use one signal and invalidate all four model
phases. The advanced form retains separate inputs. Host PhaseEpochs participate
only in declared measurement dependencies; the desktop advances its display
scale epoch. Native font and theme reconfiguration and animated presentations
remain later work. Constant/projected Presentation is not animation.

Core and graphics remain independent. A small units module owns Lp and core
re-exports its existing spelling. The standalone graphics model accepts Lp
constructors for points, rectangles, paths, translation and stroke lengths.
Its raw Double constructors remain available for existing backend coordinates;
device conversion occurs explicitly through RenderTarget.logicalToDevice.
The graphics-ui CanvasGraphics adapter exposes that exact Canvas2D, including
paths, paints, images, glyphs, instances and Drawing embedding. It does not clone
a graphics model or renderer. Core convenience scopes now also offer clipped,
transformed and layer, with lexical child handles; old spellings remain compatible.

A committed graph reader belongs to its exact capture phase. Nested custom
phases cannot reuse one; caught misuse poisons the phase. Wheel input shares
accepted local geometry with pointer hit testing. The current desktop uses its
existing 256-entry FIFO input queue: it never silently coalesces complete streams,
and explicitly refuses a different declared element capacity. More flexible
per-element transport policy remains open. The platform's existing button
accessibility transport consumes public activation semantics. Arbitrary semantic
parts and non-button roles still need native transport; host snapshots already
contain their clipped geometry.

Accepted-frame diagnostic fixtures now freeze pure Drawing values, exact font
bytes/metadata, positioned glyphs, images, dimensions and scale. Fresh-device
replay must match the captured pixels byte for byte. They never serialize element
code, UI handlers or the graph. These are trusted, same-build diagnostic artifacts,
not a versioned interchange format. Refused legacy input fixtures remain intact;
full application tests continue to reproduce layout, shaping, edits and failures.

No transaction, identity, lifetime, scheduling or command-policy semantics change.
This proves one public control path; it does not close Alpha4, the public-seam
ledger, real-client accessibility/IME qualification or any other alpha obligation.
