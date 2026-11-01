# Native semantic relationships

This implements the relationships already declared by `Semantics`. Kernel
transaction, attachment, identity, lifetime and focus policies do not change.
The owner may read an immutable list of live, committed NodeRef identities;
it never exposes mutable docking state or queries the runtime on a renderer
thread. Foreign references cannot match by a coincident numeric ID.

- Resolve relationships after assigning all native IDs in the accepted window
  tree. Forward references work. Hidden, clipped, retired, cross-window and
  modal-excluded targets have no native relationship in that presentation.
- A reference without a part addresses its first exposed semantic node, in
  document order. An explicit part addresses that unique custom-element part
  within the referenced subtree; ambiguity is omitted. Choosing a numeric
  match from another element or window was rejected. A whole custom control's
  primary semantic node remains its first part, as in existing focus publication.
- `labelledBy` and `describedBy` require a distinct exposed target. An active
  descendant must also be below the source in the native parent tree; an
  unrelated sibling is not silently treated as a descendant. Unresolved or
  invalid relationships are omitted while the control's direct name and
  description remain available. Set position is one-based; invalid pairs are
  omitted. The native ABI independently refuses dangling links and invalid pairs.
- Empty direct names/descriptions are not published as overriding native
  properties. Label nodes carry their text as their native value, including
  when another node refers to them as its label. Direct text remains a fallback.
- Text fields expose their painted error as a separate Label part and point
  `describedBy` at it through a field-owned reference. The error description
  and invalid state remain. This completes the existing `.error` API contract;
  error visibility, editing policy, focus and geometry are unchanged.
- ABI v4 adds one pointer-only metadata call: a five-element signed 64-bit
  array for label target, description target, active descendant, position and
  set size. Zero means absent. Both hosts copy the same array in bulk. No
  callback enters Scala and no new blocking extern is introduced.

Real VoiceOver behavior and semantic-part focus remain separate acceptance
work. Publishing an active-descendant relationship does not implement
`NodeRef.focusPart` or alter keyboard focus policy.
