# Live Inputs and keyed headless reconciliation

Scope: the authorized C1.5b live Inputs/ForEach/Switch slice based on `91a0ceb`.
VISION §5.2 records the semantics. Full Stage 1, native outlet/reparent operations,
widget hosting, collection deltas and virtualization remain open.

## Contracts

- Component definition token, owner and placement key establish instance identity.
  Rebinding the same dependency object keeps state; a different dependency reference
  remounts. Signal descriptor allocation does not establish component identity.
- Builders receive read-only live Inputs and run once per mount. They may declare
  cells, children, observations and leases only within their new ownership subtree.
  Captured writes, unrelated owners/runtimes, reads and disposal cannot escape that
  construction permission. Defects crossing capabilities poison the transaction.
- Same-key row replacement/reordering updates Inputs and retains drafts. Keyed
  Switch retains its branch for the same key; plain Switch remounts for unequal
  values. Owner/key/type/dependency changes retire the old instance. Source failure
  exposes Unavailable, retires the subtree, and recovers with fresh state.
- Candidate signal evaluation, delivered Inputs, structure and ownership settle
  privately and publish together. Duplicate keys, defects and budget exhaustion
  roll back structure, inputs, cells, observations and leases. Failed tentative
  mounts compensate acquired leases.
- Speculative Inputs reads follow staged source rebinding and current ancestor
  identity. Removed or retyped ancestors invalidate old Inputs. Root description
  changes clear speculative memoization. Inputs.attempt represents source failure
  as data, allowing same-transaction recovery. Committed propagation respects
  source/output equality rather than leaking raw staged changes.
- ForEach/Switch boundary identity ignores callback allocation. Retained rows keep
  originally built descriptions. New descriptions supply sources and new-key
  builders; changing captures for retained keys requires live Inputs, explicit
  component dependencies or a new boundary key. Declared Equality remains stable
  for the lifetime of the boundary.
- SchemaId distinguishes complete input/key types independently of closures and
  type erasure. Static nominal types/static object members derive automatically;
  generic helpers carry complete evidence. Abstract, dependent and local-object
  types need lawful explicit IDs. Incompatible IDs or sequence/source storage
  representations remount. Explicit IDs must distinguish incompatible types.
- Synchronous keyed cells expose an explicit keyedSnapshot. Equality includes
  ordered keys and declared element Equality. The path is O(n), not a minimal
  delta or viewport implementation. Collection events and derived filtered-source
  revisions explicitly refuse as deferred.

## Independent source review

The continuing fresh-context api_review reviewer inspected design, implementation,
regressions and the independent model without edits or builds. The main session
retained kernel ownership and ran verification.

| Finding | Resolution / regression |
|---|---|
| Rebound source reads reused speculative memo entries | Root updates invalidate memoization, including derivations from retained Inputs and refused-attempt restoration. |
| Child Inputs could read through removed/rekeyed/retyped ancestors | Controlled description providers validate the entire placement chain for nested components, rows and branches. |
| Filtered collections lost custom element equality | Ordered key/element equality now covers filtering, including signed zero. |
| User key equality could mutate through captured capabilities | Key comparisons and duplicate validation execute under the read-only guard; caught violations poison. |
| Branch/component snapshots could suppress real changes | Branch snapshots include instance generation; component props use the definition's declared Equality. Signed-zero controls cover both. |
| Fresh row/branch lambdas spuriously remounted retained state | Logical structural boundaries ignore closure allocation. The capture contract is explicit and tested. |
| Erased input types could reuse incompatible Inputs | Complete SchemaId evidence resets incompatible boundaries. Generic, nested, abstract and value-dependent negative compile controls have evidence/static positive controls. |
| Structural source failure bypassed Inputs.attempt | Source failures propagate through a private failure marker and become Value.Failed without poisoning; tests recover within the same transaction. |

Final source-review verdict: no remaining blocker in the bounded slice, conditional
on final JVM/Native checks. The last compile-control correction uses a real static
companion type: an object declared inside a typeChecks snippet is local and must
be rejected. Verification and its limits are recorded separately in
`docs/evidence/c1-reconciliation-20261027.md`.
