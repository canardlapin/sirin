# Native semantic tree publication

This continues the persistent workbench acceptance plan. The native bridge
previously published only a flat list of buttons and text inputs. Visible
modal decisions consequently lost their dialog role and explanatory labels.

- A window owns a semantic address book keyed by host NodeId and PartId.
  IDs remain stable while a part is presented. Retired IDs are never reused;
  absent keys are pruned. Hash-derived container IDs were rejected because
  collisions would silently redirect native actions. The existing positive
  48-bit control namespace and disjoint text-run namespace remain intact.
- Public semantics wrappers become native parents. Every visible custom part
  is published, with the supported role, name, value, description, checked,
  selected, invalid, busy and live state. Only the current top modal layer and
  layers above it enter the native tree. Leaving background controls exposed
  but rejecting their actions was rejected because assistive navigation would
  still escape the modal decision.
- Native actions map back to the exact presented element and part. Focus and
  Activate are supported for focusable controls and declared activation parts;
  rich text selection/replacement keeps the existing text path. Presented
  revision and current eligibility are rechecked before dispatch. Generic
  SetValue on a checkbox is refused: toggling uses its public Activate handler.
- Parent links are completed before AccessKit takes ownership of nodes. A
  snapshot with a missing parent or cycle is refused before publication. The
  previous snapshot remains available. All metadata is copied into native
  ownership; callbacks only enqueue bounded native facts and wake the owner.
- `desktop/native/accessibility.h` documents the pointer-only v2 contract.
  Both the JVM FFM and the explicit Scala Native ABI probe reach these same
  seven entry points. No structs cross by value, no callback enters Scala,
  and no new blocking extern is introduced. This probe is a macOS binding
  check, not a Native application provider or a GC qualification result.
- The original field protocol retains its 64-field limit, with 64 queued
  actions and unchanged text/span bounds. Generic nodes have a separate total
  tree limit of 1024 so the already supported 512-node diagram can be published.
  Applying the old field count to every label would make that application fail
  at presentation. Neither text capacity nor the action queue is enlarged.
  Semantic relationships
  (labelledBy/describedBy/activeDescendant), position-in-set, and actions beyond
  focus/activate/text editing remain separate transport work. Do not infer
  their support from the newly published roles.

Real VoiceOver navigation, reading and activation still require a client
session. An AccessKit debug tree or AX automation is not that evidence.
