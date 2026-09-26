# Initial threat model

## Assets

Effective authority, stable subject-to-role bindings, policy compiler output, budget conservation, state mutation history, decision evidence, signing keys, and availability of safety invariants.

## Adversaries and failure sources

- A malicious or buggy plugin attempts unregistered writes, floods checks, forges attribution, or supplies false context.
- A policy author uses a broad wildcard, role cycle, timezone mistake, or an accidental region union.
- A compromised moderator creates an off-hours grant burst or self-escalates.
- A distributed peer replays, duplicates, reorders, or sends a conflicting mutation under an existing event ID.
- A disk/operator edits or truncates ledger bytes, deletes an encryption key, or restores a stale checkpoint.
- Wall-clock skew, dependency loss, stale role cache, missing region snapshot, or exhausted budget causes a decision-time degradation.

## Security choices in this slice

- Default deny; strict schema; no display-name subject field; immutable action/role snapshots per check.
- Barriers and Selvedge invariants precede mutable policy; ceilings are checked at compile time for role-owned grants.
- Budget check and reservation are one synchronized operation; caller receives a single-use lease.
- Audit links use HMAC-SHA256 and constant-time signature comparison; token delegation cannot widen pattern scope or expiry.
- Weft conflict or invariant failure quarantines locally; no auto-merge of a rejected candidate.
- Public explanations are rendered from records but do not expose rule IDs or subject IDs.

## Explicitly unresolved before production

Key storage/rotation and external HSM use; durable and multi-region revocation; signed mutation provenance; persistent HLC/event transport; ledger checkpoint anchoring against a privileged operator; crash-safe storage/fsync/backup recovery; legal-erasure retention review; API TLS/HTTP middleware; permission-context provenance; Bukkit/Paper/Velocity thread-safety; DoS limits across all caches; formal review of pattern coverage and override logic; and an independent security assessment.

This document is not a certification or claim that the prototype is safe to deploy on a live network.
