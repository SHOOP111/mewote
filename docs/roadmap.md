# LATTICE delivery roadmap and evidence gates

The requested complete platform spans multiple deployable systems. Work is sequenced as vertical slices; a name in this roadmap is a target, not a shipped module. **Only the items in Phase 0 status below are implemented.**

| Phase | Target outcome | Acceptance gate | Status |
|---|---|---|---|
| 0 — vertical slice | Heddle compiler/lint, Warp deterministic resolver, Thread disclosure rendering, starter pack, CLI compatibility entry point | CI law/corpus suite green; package install; explicit supported semantics | Prototype implemented; CI green on `bc1a979`; no Minecraft adapter |
| 1 — memory | Skein event-sourced state ledger, checkpoints, reconstruction, export/restore | mutation/hash-chain tampering tests; checkpoint replay equivalence; deletion/crypto-shred review | Not started |
| 2 — authority | Selvedge invariants/ceilings, Sigil tokens, Quanta budgets and atomic leases | property laws for ceilings, non-escalation, reserve/refund conservation, revocation | Not started |
| 3 — reach | Weft HLC sync, Ply canonical identity, Tessellation immutable indexed spatial snapshots | partition/duplicate/skew/divergent-edit chaos; merge quarantine; declared propagation bound | Not started |
| 4 — understanding | Loom explanation console, graph, temporal reconstruction, blast-radius and review flows | accessibility, authorization and realistic scale tests; no fabricated history | Not started |
| 5 — safety | Replay shadow traffic, Vigil anomaly/circuit breakers, Palette packs, Shuttle migration | zero/accepted divergence; rollback and importer semantic-gap tests | CLI offline verdict replay only; rest not started |
| 6 — scale and polish | Beacon observability, Bastion API security, Bridge server/plugin adapter, docs and hardening | soak, security review, reproducible benchmarks, ecosystem compatibility | Not started |

## Release rule

A target is not complete because an interface exists. It needs its spec, edge/failure cases, executable laws, real corpus, CI evidence, and benchmark artifacts where performance is claimed. No performance figures, convergence bounds, “100% coverage,” mutation score, or production-readiness claim are made by this repository today.

The first next step is to run the Phase 0 workflow and resolve any failures. After that, complete an actual Minecraft server adapter and subject/role binding model before adding broad platform surface. Skein's event semantics and Weft's merge/invariant behavior require design review before implementation, as they determine whether historical and distributed claims can be true.
