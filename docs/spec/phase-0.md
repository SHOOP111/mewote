# Phase 0 design review: policy, resolution, evidence

Status: **implemented prototype; CI evidence pending**. This document constrains the current implementation and identifies deliberately unsupported semantics. It is not a claim of production readiness.

## Responsibilities and boundaries

- **Heddle** parses version-1 JSON, type-checks rule fields, rejects malformed patterns and lifecycle windows, emits broad-pattern/duplicate/unbounded-authority warnings, and sorts rules once at compile time.
- **Warp** evaluates immutable compiled rules against a permission, a stable subject key supplied by the host, and a context snapshot. One policy has no runtime sort or candidate list. Matching is presently a linear scan; no benchmark claim is made.
- **Thread** renders the same decision record as public, staff, or audit text. Public output is deliberately generic. Permalinks, signatures, translation catalogs, and scoped audit access are not implemented.
- **CLI** provides starter installation, lint, check, explain, and offline verdict-difference replay.

The host must normalize identity before calling Warp. A display name is not a valid identity contract; the present Python kernel does not yet provide Ply or enforce UUID/XUID canonicalization.

## Total resolution order

Lower band number wins. The global order is:

| Rank band | Meaning | Current rule form |
|---:|---|---|
| 0 | Compiled-in invariant denial | `authority: invariant`, `effect: deny` |
| 1 | Absolute barrier | `authority: invariant`, `effect: barrier` |
| 2 | Explicit deny | `effect: deny` |
| 3 | Explicit grant | non-inherited `effect: allow` |
| 4 | Inherited grant | `effect: allow`, `inherited: true` |
| 5 | Namespace default | `authority: default`, `effect: allow` |
| — | Abstain | absence of a candidate; never a stored rule |

Within a band: more literal segments, then a pattern without a multi-tail wildcard, then more fixed segments, then higher signed 32-bit priority, then later source order, then ascending stable rule ID. The final cross-policy tie-break also compares source name and canonical rule content. That comparison is total. Since effect determines the band for ordinary dynamic rules, deny beats allow before within-band comparisons. Invariant authority is restricted to deny/barrier rules. Barrier rules are restricted to invariant authority. A missing candidate produces a fail-closed denial with `band: abstain`; it is not represented as an attributable policy rule.

With multiple independently compiled authorities, the kernel compares each eligible rule's immutable semantic ordering key and does not sort the candidates. Put authorities in one compiled document to use the single-policy rank scan. No authority may emit a candidate to mean “abstain.” Skipping an authority cannot affect a result unless that authority had the winning candidate.

## Pattern grammar

`permission := segment ('.' segment)*`; a segment is a non-empty literal, `*`, or final `**`. `*` consumes exactly one segment. Final `**` consumes zero or more trailing segments, including zero (`a.**` matches `a`). Wildcards must occupy whole segments. `**` in a middle position, multiple `**`, embedded stars, and empty segments are compile errors. Extremely broad `**`/`*.**`-style patterns lint as warnings. A warning is not a safe substitute for review.

## Guards and lifecycle

Every rule has an object-valued guard and lifecycle (empty objects mean unconstrained). Phase 0 guards support exact world, region, game-mode, persona, min/max health, and explicit `plugin:<field>` context equality. Missing requested data fails closed. Arbitrary plugin context is caller-provided and not trusted without host-side validation. Time schedules and cron/zone evaluation are rejected; lifecycle UTC validity timestamps are supported. Times must carry an offset. `valid_from` is inclusive and `expires_at` exclusive.

A policy may declare a positive integer `budget.capacity` and `budget.period_seconds` for schema forward-compatibility, but Phase 0 does not enforce, reserve, or refund budgets. The linter says so. Do not rely on these fields for access control until Quanta and atomic leases are implemented.

## Evidence record and disclosure

Each result contains schema, subject, permission, verdict, winning rule (if any), band, authority, reason code, and normalized UTC evaluation time. Canonical JSON uses sorted keys and compact separators before SHA-256. Equal state/question/time gives byte-identical record and digest. The digest is an integrity fingerprint only: it is not a signature, append-only ledger, or proof of historical state.

Public rendering gives a generic decision and an access-request path. Staff rendering includes rule and node. Audit rendering serializes the evidence record. Public output does not include audit-only fields. Authentication/authorization of disclosure is the caller's responsibility and is not implemented by the CLI.

## Failure modes and deliberate behavior

- Invalid schema, unknown guard, duplicate ID, bad wildcard, malformed timestamp, or unsupported effect: compile fails with a field-oriented diagnostic; no partial policy is returned.
- Invalid permission query or timezone-naive evaluation time: request fails; it is not silently normalized.
- No matching allow: deny. Inactive/expired rules do not match.
- Missing guard context: guarded rule does not match.
- Invalid budget declaration: compile failure; valid declaration: warning that enforcement is absent.
- Invalid candidate traffic line: replay fails with source line; partial replay is not reported as success.
- Multiple matching rules: deterministic total ordering above, independent of hash map iteration.

## Executable acceptance criteria

The workflow tests the following claims: wildcard arity and compile rejection; strict precedence bands; specificity/priority/source/stable-ID ordering; barrier resistance to dynamic grants; abstention non-interference; context and expiry boundaries; timezone rejection; stable records/digests; public nondisclosure; duplicate-ID/lifecycle validation; and 1,200 seeded generated exact-over-broad decisions. The suite is a regression net, not a formal proof. Generated cases are deterministic and reproducible, but do not shrink.

## Explicit Phase 0 exclusions

No inheritance graph/cycle detection, capability-set ceilings, dynamic approval separation, spatial geometry, schedules, budgets/leases, signed capability tokens, server permission API bridge, durable event ledger, checkpoint/reconstruction, distributed HLC merge, stable identity registry, web console, live change propagation, anomaly detector, secure plugin registration, or Minecraft server adapter exists yet. These exclusions prevent unsupported guarantees from being implied.
