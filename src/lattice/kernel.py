"""Warp: deterministic rule evaluation and Thread-compatible evidence records."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
import hashlib
import json
from types import MappingProxyType
from typing import Any, Mapping, Sequence

from .compiler import CompiledPolicy, Rule


class Effect:
    ALLOW = "allow"
    DENY = "deny"
    BARRIER = "barrier"
    ABSTAIN = "abstain"


@dataclass(frozen=True, slots=True)
class Context:
    subject: str
    world: str | None = None
    region: str | None = None
    game_mode: str | None = None
    persona: str | None = None
    health: float | None = None
    at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    attributes: Mapping[str, Any] | None = None

    def __post_init__(self) -> None:
        if self.attributes is not None:
            object.__setattr__(self, "attributes", MappingProxyType(dict(self.attributes)))


@dataclass(frozen=True, slots=True)
class Decision:
    verdict: str
    rule_id: str | None
    band: str
    permission: str
    subject: str
    authority: str | None
    reason_code: str
    record: Mapping[str, Any]
    record_digest: str

    def explain(self, disclosure: str = "public") -> str:
        """Render the same evidence at a disclosure level; never mutate the record."""
        if disclosure not in {"public", "staff", "audit"}:
            raise ValueError("disclosure must be public, staff, or audit")
        if disclosure == "public":
            if self.verdict == Effect.ALLOW:
                return f"Allowed: this action is available to you."
            return "Not allowed: your current access does not include this action. You can request access from a server moderator."
        if disclosure == "staff":
            return (f"{self.verdict.upper()} {self.permission} for {self.subject}: "
                    f"{self.band} via {self.rule_id or 'no matching rule'} ({self.reason_code}).")
        return json.dumps(dict(self.record), sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def _guard_matches(rule: Rule, context: Context) -> bool:
    g = rule.guard
    for field, value in (("world", context.world), ("region", context.region),
                         ("game_mode", context.game_mode), ("persona", context.persona)):
        if field in g and g[field] != value:
            return False
    if "min_health" in g and (context.health is None or context.health < g["min_health"]):
        return False
    if "max_health" in g and (context.health is None or context.health > g["max_health"]):
        return False
    now = context.at
    if now.tzinfo is None or now.utcoffset() is None:
        raise ValueError("Context.at must include a timezone/UTC offset")
    now = now.astimezone(timezone.utc)
    if rule.valid_from and now < rule.valid_from.astimezone(timezone.utc):
        return False
    if rule.expires_at and now >= rule.expires_at.astimezone(timezone.utc):
        return False
    attrs = context.attributes or {}
    # Plugin context is explicit: unknown context keys simply fail closed when requested.
    for key, expected in g.items():
        if key.startswith("plugin:") and attrs.get(key) != expected:
            return False
    return True


def _record_digest(record: Mapping[str, Any]) -> str:
    encoded = json.dumps(dict(record), sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    return hashlib.sha256(encoded).hexdigest()


def evaluate(policy: CompiledPolicy | Sequence[CompiledPolicy], permission: str,
             context: Context, *, abstaining_authorities: frozenset[str] = frozenset()) -> Decision:
    """Resolve independently compiled authorities using a single total-order rank.

    Rank is assigned at compile time across one policy. For multiple authorities,
    candidate ordering is represented by a deterministic tuple whose first member
    is the global precedence band. Abstention contributes no candidate.
    """
    if (not isinstance(permission, str) or not permission
            or any(not part or "*" in part for part in permission.split("."))):
        raise ValueError("permission must be a concrete, non-empty dot-separated node")
    if not isinstance(context.subject, str) or not context.subject.strip():
        raise ValueError("subject must be a non-empty stable identifier")
    if not isinstance(context.at, datetime) or context.at.tzinfo is None or context.at.utcoffset() is None:
        raise ValueError("Context.at must include a timezone/UTC offset")
    policies = (policy,) if isinstance(policy, CompiledPolicy) else tuple(policy)
    band_names = {0: "invariant", 1: "barrier", 2: "explicit_deny", 3: "explicit_grant",
                  4: "inherited_grant", 5: "namespace_default"}

    def eligible(rule: Rule) -> bool:
        return (rule.authority not in abstaining_authorities
                and (rule.subject is None or rule.subject == context.subject)
                and rule.pattern.matches(permission)
                and _guard_matches(rule, context))

    winner: Rule | None = None
    if len(policies) == 1:
        # Rules are rank-ordered at compile time. The hot path walks an immutable
        # sequence and stops at its first eligible entry: no runtime sorting and
        # no candidate list. A compact trie/index replaces this scan in later scale work.
        for rule in policies[0].rules:
            if eligible(rule):
                winner = rule
                break
    else:
        # Independently compiled authorities retain exact semantics; compare each
        # eligible candidate against the current winner without sorting/allocating.
        winning_key: tuple[Any, ...] | None = None
        for compiled in policies:
            for rule in compiled.rules:
                if not eligible(rule):
                    continue
                key = (rule.band, -rule.pattern.specificity[0], -rule.pattern.specificity[1],
                       -rule.pattern.specificity[2], -rule.priority, -rule.source_order,
                       rule.id, compiled.source_name, rule.stable_key)
                if winning_key is None or key < winning_key:
                    winning_key, winner = key, rule
    if winner is not None:
        verdict = winner.effect
        band = band_names[winner.band]
        reason = "matched_rule"
        rid = winner.id
        authority: str | None = winner.authority
    else:
        verdict, band, reason, rid, authority = Effect.DENY, "abstain", "no_matching_grant", None, None
    # Stable canonical primitive-only record; timestamps use normalized UTC.
    at = context.at.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    record = {
        "schema": 1, "subject": context.subject, "permission": permission,
        "verdict": verdict, "band": band, "rule_id": rid, "authority": authority,
        "reason_code": reason, "evaluated_at": at,
    }
    frozen_record = MappingProxyType(record)
    digest = _record_digest(frozen_record)
    return Decision(verdict, rid, band, permission, context.subject, authority, reason, frozen_record, digest)
