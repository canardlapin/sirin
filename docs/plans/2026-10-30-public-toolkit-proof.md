# Public toolkit proof and execution sequence

Owner-approved course correction, 2026-10-30. Keep all six alpha obligations;
concentrate implementation on one complete public path at a time.

1. Consolidate existing work and preserve the Mote store, running app and frozen
   launch candidate. Remove obsolete clean worktrees after artifact preservation.
2. Prove Element/Layout, full graphics access, one-state authoring and one built-in
   control through that same API. Button is the first port. A diagram alone is
   insufficient proof, and the full diagram no longer blocks starting D1.
3. Exercise the seam in the diagram, then finish the remaining control migrations,
   especially public text input/composition and text-range semantics for TextField.
   Motion remains inside the alpha and before API freeze.
4. Concentrate application validation on the persistent workbench, real clients,
   coherent interaction and visual quality. Bring independent feature authoring
   and a small matched ScalaFX comparison forward; keep their acceptance separate
   from automated kernel and rendering tests.
5. Give Native GC a bounded causal investigation with an explicit delivery-order
   decision before Alpha6. JVM development/validation continues meanwhile. The
   proposed fallback is a JVM-first alpha if Native remains blocked; this tranche
   neither fixes P0.6 nor claims a qualified Native application host.

## First proof

- Public consumer example: examples/authoring/consumer/CurveControl.scala.
- Single-state and per-phase invalidation, layout identity and named-child retention.
- Pure phase, owner-thread, stale instance and lexical capability enforcement.
- Shared Canvas2D model, logical constructors, recorded drawings and lexical scopes.
- Button pointer/keyboard/command behavior, enablement, semantics and feedback.
- Prepared measurement and paint text; color/feedback changes do not reshape text.
- Full JVM/Native tests, authoring compilation and desktop tests.

The diagram archive 0413beb is reference material. Do not restore it wholesale.
The public-seam ledger remains open. Native transport for arbitrary custom semantic
parts, non-button roles, configurable event capacities, platform font/theme
reconfiguration and animation require subsequent qualification.

## Operating rules

Use a task worktree, merge validated changes to main, and retire the worktree.
Keep formatting changes separate from behavior changes. Commit a short evidence
note; keep raw captures, fixtures and logs outside Git with hashes. Update
STATUS.md when capability changes, without promoting a bounded proof to alpha
acceptance. Profile D7 on the actual application path before prioritizing copies.

## Diagram tranche

The first client after the seam proof is a new `demo.diagram` implementation;
archive 0413beb remains preserved. Gesture previews are application state and do
not mutate committed graph history. Release commits one history item; cancellation,
focus loss and retirement discard previews. This changes no kernel transaction,
identity, lifetime, scheduling or command policy.

The original dense fixture exposed native resource admission failures. Pending
programs now flush before their combined pinned inventory would exceed existing
limits. A tile whose own program exceeds those limits is subdivided, with bounded
partition metadata for subsequent reuse. Native pools, pixel limits and tile-count
limits are unchanged. Independent reference pixels, cancellation, refusal and
recovery checks cover this path; the C shim and ABI are unchanged.

Automated public-client and packaged-image checks are a bounded Alpha4 step.
Real pointer use, native semantic-part transport, owner visual review and the
remaining Alpha4 acceptance criteria stay open.

TextField now runs on the public seam (text input, composition, focus events,
editing services and text-range semantics), and the host's private editor is
gone; see `docs/design/decisions/2026-10-30-textfield-port.md`. Real IME and
VoiceOver sessions on the port remain unqualified. The next implementation steps
are motion, which must land before the alpha API freezes, and moving the
remaining layout controls (`Text`, `Row`, `Column`, `Scroll`, `VirtualRows`,
`DockOutlet`) onto the seam.
