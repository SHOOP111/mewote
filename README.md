# LATTICE

**LATTICE governs authority.** It is a Minecraft access-control platform designed around deterministic decisions, attributable reasons, and evidence that can be tested.

> **Honest status:** this repository starts from an empty skeleton. It now contains a runnable Phase 0 Python vertical slice: Heddle's versioned JSON policy compiler/linter, Warp's deterministic decision kernel, Thread's three disclosure renderings, a survival starter pack, a CLI replay command, and GitHub Actions law tests. CI passed on commit `bc1a979` ([run 36246410362](https://github.com/SHOOP111/mewote/actions/runs/36246410362)). It is not yet a Minecraft plugin and does not yet implement the state ledger, leases/budgets, distributed mesh, identity provider, spatial index, visual console, or the later phases. Do not describe it as production-ready or as proving historical authority.

## Start in ten seconds

Requires Python 3.11+.

```sh
python -m pip install .
lat init --pack survival
lat lint lattice-policy.json
lat explain lattice-policy.json server.spawn.teleport --subject java:example-id --disclosure staff
```

A denial can be inspected at three levels:

```sh
lat explain lattice-policy.json server.admin.reload --disclosure public
lat explain lattice-policy.json server.admin.reload --disclosure staff
lat explain lattice-policy.json server.admin.reload --disclosure audit
```

The public rendering intentionally omits node names, rule IDs, authority sources, and internal reason codes while providing a request path. The audit rendering is the canonical decision record; its SHA-256 digest is printed separately. This is **not** a signed audit chain.

Candidate replay accepts JSON Lines, one query per line:

```json
{"permission":"server.home.use","subject":"java:uuid","world":"overworld","at":"2026-09-26T12:00:00Z"}
```

```sh
lat simulate current.json candidate.json traffic.jsonl
```

Exit codes: `0` means no verdict differences (or a successful command); `1` is a denied `lat check`; `2` indicates invalid input or replay differences. Replay compares verdicts only; it is not shadowing a live server.

## Phase 0 policy semantics

See [`docs/spec/phase-0.md`](docs/spec/phase-0.md) for the precedence law, grammar, edge cases, failure behavior, tests, and known limits. The compiler rejects middle wildcards and naive timestamps. It assigns immutable ranks at compile time; the single-policy kernel scans rank order and stops at the first matching eligible rule without runtime sorting. Rule patterns are currently scanned linearly, not served by a trie/index, so no latency target is claimed.

## Evidence

The repository's CI workflow is the authoritative test and compile surface. It installs the package, runs deterministic and generated law tests with `unittest`, and lints the bundled policy. To verify a change, push this branch and inspect the **Verify LATTICE** GitHub Actions run. No local test/compile result is claimed here.

Tests currently include 1,200 seeded generated specificity/determinism cases plus fixed laws and edge cases. This is not exhaustive proof, mutation testing, 100% decision-point coverage, or the curated real-server policy corpus described in the long-term mandate.

## Development status and phases

The phase plan and non-goals are recorded in [`docs/roadmap.md`](docs/roadmap.md). Each feature remains marked incomplete until executable acceptance evidence exists. The repository currently implements a deliberately narrow first slice rather than implying that all requested subsystems are complete.
