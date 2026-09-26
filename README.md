# LATTICE

**LATTICE governs authority.** It is a deterministic, evidence-producing permission kernel for Minecraft networks, designed around stable identities, explainable decisions, safe policy changes, and reconstructable state.

This repository begins with a deliberately narrow, executable vertical slice: **Warp** resolution, **Heddle** policy compilation/linting, **Thread** disclosure-tier explanations, **Selvedge** invariant checks, and testable foundations for the later subsystems. The phase status is explicit in [`docs/status.md`](docs/status.md); components marked prototype are not production guarantees.

## Run the evidence suite

Compilation and tests are run by the GitHub Actions workflow in `.github/workflows/ci.yml` (push, pull request, or manual dispatch). The workflow publishes JUnit reports and JaCoCo branch-coverage evidence. Do not infer a correctness or performance claim from a local build that is not represented by this workflow.

The policy and CLI contract is documented in [`docs/policy-v1.md`](docs/policy-v1.md). A reviewed survival starter policy is at [`src/main/resources/policies/survival.json`](src/main/resources/policies/survival.json).

## Current claims and limits

- Same compiled policy, request, and context produce the same canonical reasoning record.
- A barrier cannot be overridden by policy rules below the compiled-in invariant layer.
- Rule ordering is a compiled total order; the request path never sorts rules.
- Rule guards include half-open validity windows, weekly schedules with explicit time zones, and typed context equality.
- Budget authorization uses a single atomic reserve operation and returns a lease that can be committed or refunded.
- The state-ledger, mesh, identity, spatial, token, replay, and observability packages are isolated prototypes; they are not yet durable, networked, or certified for production use.

No benchmark number is published yet. The GitHub workflow records coverage, not a latency claim. No release claims 100% decision-point coverage until the generated report demonstrates it.

## Ten-second CLI

`bin/lat` runs the executable shaded JAR produced by the Maven package phase:

```sh
bin/lat init --pack survival
bin/lat lint
bin/lat demo
bin/lat explain lattice.policy.json 11111111-1111-4111-8111-111111111111 minecraft.build.place --role builder --world survival --region spawn
```

`lat init` refuses to overwrite a policy unless `--force` is explicit. `lat explain` exits with status 3 for a denial so scripts can distinguish it from an operational error. This CLI does not connect to a Minecraft server; the `demo` and Loom data are illustrative. Server-specific Bridge adapters remain incomplete.

## Evidence map

The executable claims and their negative tests are listed in [`docs/evidence.md`](docs/evidence.md). The workflow is the authoritative place to compile and execute them; JaCoCo and JUnit reports are uploaded for each run.
