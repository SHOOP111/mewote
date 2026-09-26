# Delivery status

This status is intentionally candid. A directory or type name does not imply a production-ready subsystem.

| Phase | Target | Repository status |
|---|---|---|
| 0 — vertical slice | Kernel, compiler/linter, disclosure tiers, compatibility contract, laws | In progress. Warp/Heddle/Thread/Selvedge are implemented as the first executable slice; Bridge adapters and server integration remain incomplete. |
| 1 — memory | Durable event state, checkpoints, reconstruction, backup/restore | Prototype contracts only until durable storage and restart/erasure tests pass. |
| 2 — authority | Invariants, ceilings, signed capabilities, atomic budgets | Core ceiling checks and budget leases are in scope; distributed revocation and durable token revocation remain incomplete. |
| 3 — reach | Sync, spatial model, stable identity | Isolated library prototypes; no server transport or declared network SLA is shipped. |
| 4 — understanding | Loom console, graph, heatmaps, blast-radius planning | Not production-ready. |
| 5 — safety | Replay, anomaly detection, starter packs, migration | Starter policy and deterministic replay foundations only; no live shadow deployment. |
| 6 — scale and polish | Observability, API security, chaos/soak, audit | Not complete. |

## Evidence gates

A phase is complete only when its acceptance tests run in GitHub Actions, coverage artifacts are published, failure-mode tests pass, and claims in its docs are reproducible. CI is not an automated prover and a green build is not a security certification. Performance and network-latency targets remain targets until measured in the production server environment.
