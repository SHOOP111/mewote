# Laws, tests, and evidence status

This mapping is intentionally conservative: a test demonstrates only the paths it actually exercises. GitHub Actions runs `mvn clean verify` and publishes JUnit and JaCoCo artifacts; the artifacts, not this table, contain the run's counts.

| Law / claim | Test evidence | Current caveat |
|---|---|---|
| Deterministic record for equal policy/request/context | `WarpHeddleThreadTest.decisionRecordIsCanonicalAndDisclosureIsLeastPrivilege`; generated 2,000-policy property | Budget state is part of the effective state; consuming a lease changes the next answer. |
| Total ordering; no runtime sort | `PolicyCompiler.TOTAL_ORDER`, indexed rank arrays; priority/source-order tests; property suite | Authority resolution still allocates evidence lists; zero-allocation claim is not made. |
| Barrier cannot be overridden by lower policy | barrier precedence test; 1,500 generated matching patterns/priorities | Selvedge itself is a partial invariant catalogue, not a proof of all Minecraft exploit paths. |
| Soft deny vs barrier | specific allow overrides broad deny; same-specificity deny wins; barrier terminal tests | Normative detail is in `resolution-model.md`. |
| Segment wildcard grammar; middle `**` rejected | pattern tests and compile errors | Heddle v1 accepts JSON only. |
| Role graph transitive and cycle-free | cycle negative test; 1,000 generated acyclic chains | Role memberships must be refreshed by the eventual identity adapter. |
| Capability ceilings apply to every role-owned allow | compile-time conservative inclusion check and escape negative test | Union coverage by multiple ceiling patterns is intentionally not inferred. |
| Abstain cannot alter outer verdict | `composeVerdict` assertions and independent authority indexes | Full server/plugin authority registration lifecycle remains incomplete. |
| Context schedule uses declared timezone and half-open interval | New York DST and UTC overnight tests | Broader calendar recurrence/holiday calendars are not in schema v1. |
| Budget reservation is atomic; refund is exact | 200-way concurrency test, settlement-state tests, conservation accounting | Distributed/cross-server budget propagation is not implemented. |
| State chain detects content edits; checkpoint reconstruction; crypto-shred | `SubsystemLawsTest.ledgerReconstructsFromCheckpointAndCryptoShreddingPreservesTheChain` | In-memory prototype; crash durability/external hash anchoring are absent. |
| Merge is deterministic, idempotent, invariant-aware, revocation-monotonic | HLC tests, merge ordering property, duplicate/conflict/quarantine tests | No actual mesh, message authentication, partition soak, or measured propagation SLA. |
| No mutable name is an authority key | Ply rename and display-name rejection tests; policy subject parser accepts canonical UUID only | Platform authentication and verified Bedrock linking are adapter responsibilities. |
| Public explanation omits audit-only rule/subject IDs | disclosure-tier tests | Public wording is English/Spanish sample catalog; production translation review remains. |
| Signed links are scoped and expiring | HMAC tamper, viewer scope, subject, expiry tests | Link storage/authorization endpoint is not implemented. |
| Replay reports behavioral differences | replay verdict-transition test | Real traffic recording and canary rollback are not implemented. |
| Rogue plugin cannot self-register/expand registered writes | Bastion tests | No HTTP server or operational key lifecycle is implemented. |
| Never claim unmeasured performance | workflow publishes coverage; no benchmark numbers in docs | JMH harness and server load tests are outstanding; no latency target is claimed as achieved. |

The test suite is an evidence pack in progress, not the requested release gate: mutation testing, 100% decision-point coverage, full real-world policy corpus, chaos/soak, signed external audit anchors, and a server adapter have not shipped.
