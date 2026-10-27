# Headless topology and scoped lifetime decisions

Scope: C1.2c dynamic signal settling and the C1.5a headless lifecycle slice,
based on `645df95`. VISION §5 remains the design authority; it explicitly
records these implementation contracts. Full component reconciliation,
commands/channels and native retirement remain separate gates.

## Contracts made concrete

- `flatMap` has stable explicit output Equality. Selection runs when its input
  changes. Selected descriptions may be newly allocated. Topology edits apply
  between private evaluation passes. Cycles and exhausted budgets reject the
  entire candidate, preserving committed values, revisions and edges.
- Speculative reads follow staged selections without changing committed edges.
  Runtime reads pin roots until close; scoped reads/observations pin them only
  for their ownership lifetime. Traversal alone never roots detached branches.
- Scope/cell/watch creation and disposal join staging. Scoped refusal restores
  the prior graph and lifetime; defects poison the transaction. Staged disposal
  blocks further owner operations. Successful publication retires the owner and
  descendants, disconnects their observations and removes queued effects.
- Scope names are diagnostic. The explicit headless static mount has
  (owner, position, definition) identity. A changed definition resets that slot;
  duplicate same-definition placement fails. Generation-checked callbacks and
  owner-checked afterCommit hooks cannot reach a replacement scope.
- A provisional cell has no durable committed snapshot. Its first publication
  uses the final staged initialization at revision zero, even under custom
  equality. Subsequent unequal commits advance revisions once.
- onDispose transfers an acquired external lease. Rollback compensates newly
  transferred leases; committed disposal releases existing leases. Release
  callbacks run once under read-only guards, with independent error reporting.
  This is not native/GPU retirement.

## Independent review

Fresh-context `api_review` reviewed the design risks, implementation and oracle
without edits/builds. Main session retained kernel ownership.

| Finding | Resolution and regression |
|---|---|
| P1: a disposed/provisional source attached after pass 1 could publish its initial value | Source validity checked on every pass. Observer-trace tests cover disposed and refused-mount cells, same-transaction disposal/selection, and wrapping derivations. |
| P2: generation-checked delivery ignored staged ancestor disposal | Delivery consults the active disposal set. Tests cover staged child/parent disposal, workspace writes and restoration after refused disposal. |
| P2: mount-then-write revisions disagreed between engine and model, and the generator missed it | Defined first publication at revision zero; uncommitted nodes publish final initialization including custom-equal values. Generated mount/write/restore/attempt forms and deterministic descendant/refusal scripts use the shared comparison harness. |

Final verdict: **no remaining blocker found in the bounded topology/lifetime
implementation or reviewed oracle fixes, conditional on final JVM/Native checks**.
The reviewer inspected sources only. Passing those checks is recorded separately
in `docs/evidence/c1-topology-20261027.md`. This is not full Stage 1 approval.
