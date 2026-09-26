"""Heddle Phase 0: parse, validate, lint, and pre-order policy rules."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import json
from pathlib import Path
import re
from types import MappingProxyType
from typing import Any, Mapping

SCHEMA_VERSION = 1
_EFFECT_BANDS = {
    "barrier": 1,
    "deny": 2,
    "allow": 3,
    "abstain": 6,
}
_BAND_NAMES = {
    0: "invariant",
    1: "barrier",
    2: "explicit_deny",
    3: "explicit_grant",
    4: "inherited_grant",
    5: "namespace_default",
    6: "abstain",
}
_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


class PolicyError(ValueError):
    """A source-located, actionable policy validation error."""


@dataclass(frozen=True, slots=True)
class Pattern:
    source: str
    segments: tuple[str, ...]
    literals: int
    specificity: tuple[int, int, int]
    loose: bool

    def matches(self, permission: str) -> bool:
        parts = tuple(permission.split("."))
        if not permission or any(not p for p in parts):
            return False
        tail = self.segments[-1] == "**"
        fixed = self.segments[:-1] if tail else self.segments
        if (tail and len(parts) < len(fixed)) or (not tail and len(parts) != len(fixed)):
            return False
        for expected, actual in zip(fixed, parts):
            if expected != "*" and expected != actual:
                return False
        return True


def _compile_pattern(value: Any, where: str) -> Pattern:
    if not isinstance(value, str) or not value:
        raise PolicyError(f"{where}: permission must be a non-empty dot-separated string")
    segments = tuple(value.split("."))
    if any(not segment for segment in segments):
        raise PolicyError(f"{where}: empty permission segment in {value!r}")
    if any("*" in s and s not in {"*", "**"} for s in segments):
        raise PolicyError(f"{where}: wildcard must occupy an entire segment ('*' or '**')")
    if "**" in segments[:-1]:
        raise PolicyError(f"{where}: '**' is allowed only as the final segment")
    if segments.count("**") > 1:
        raise PolicyError(f"{where}: only one trailing '**' is allowed")
    literals = sum(s not in {"*", "**"} for s in segments)
    loose = literals == 0 or (segments[-1] == "**" and literals <= 2)
    fixed = segments[:-1] if segments[-1] == "**" else segments
    return Pattern(value, segments, literals, (literals, int(segments[-1] != "**"), len(fixed)), loose)


def _parse_time(value: Any, where: str) -> datetime | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise PolicyError(f"{where}: expected an ISO-8601 timestamp with a UTC offset")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise PolicyError(f"{where}: invalid ISO-8601 timestamp {value!r}") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise PolicyError(f"{where}: timestamp must include a timezone/UTC offset")
    return parsed


@dataclass(frozen=True, slots=True)
class Rule:
    id: str
    pattern: Pattern
    effect: str
    band: int
    authority: str
    subject: str | None
    priority: int
    source_order: int
    guard: Mapping[str, Any]
    valid_from: datetime | None
    expires_at: datetime | None
    budget: Mapping[str, Any] | None
    stable_key: str
    rank: int

    @property
    def band_name(self) -> str:
        return _BAND_NAMES[self.band]


@dataclass(frozen=True, slots=True)
class CompiledPolicy:
    version: int
    rules: tuple[Rule, ...]
    source_name: str = "<memory>"


def load_policy(path: str | Path) -> dict[str, Any]:
    file = Path(path)
    try:
        value = json.loads(file.read_text(encoding="utf-8"))
    except OSError as exc:
        raise PolicyError(f"{file}: cannot read policy: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise PolicyError(f"{file}:{exc.lineno}:{exc.colno}: invalid JSON: {exc.msg}") from exc
    if not isinstance(value, dict):
        raise PolicyError(f"{file}: policy root must be an object")
    return value


def lint_policy(document: Mapping[str, Any]) -> list[str]:
    """Return stable warning strings. Invalid policies raise PolicyError."""
    compiled = compile_policy(document)
    warnings: list[str] = []
    seen: set[tuple[int, str, str | None]] = set()
    for rule in compiled.rules:
        if rule.pattern.loose:
            warnings.append(f"{rule.id}: pattern {rule.pattern.source!r} is extremely broad")
        key = (rule.band, rule.pattern.source, rule.subject)
        if key in seen:
            warnings.append(f"{rule.id}: duplicate pattern in {rule.band_name}; inspect precedence")
        seen.add(key)
        if rule.expires_at is None and rule.effect in {"allow", "barrier"}:
            warnings.append(f"{rule.id}: {rule.effect} has no expiry (review long-lived authority)")
        if rule.budget is not None:
            warnings.append(f"{rule.id}: budget is declared but not enforced by Phase 0; do not rely on it until Quanta ships")
    return warnings


def compile_policy(document: Mapping[str, Any], source_name: str = "<memory>") -> CompiledPolicy:
    if document.get("schema_version") != SCHEMA_VERSION:
        raise PolicyError(f"schema_version: expected {SCHEMA_VERSION}; got {document.get('schema_version')!r}")
    raw_rules = document.get("rules")
    if not isinstance(raw_rules, list):
        raise PolicyError("rules: expected an array")
    pending: list[dict[str, Any]] = []
    ids: set[str] = set()
    for order, raw in enumerate(raw_rules):
        where = f"rules[{order}]"
        if not isinstance(raw, dict):
            raise PolicyError(f"{where}: expected an object")
        rid = raw.get("id")
        if not isinstance(rid, str) or not _ID_RE.fullmatch(rid):
            raise PolicyError(f"{where}.id: expected 1-128 stable ASCII letters, digits, '.', '_', ':', or '-'")
        if rid in ids:
            raise PolicyError(f"{where}.id: duplicate rule id {rid!r}")
        ids.add(rid)
        effect = raw.get("effect")
        if not isinstance(effect, str) or effect not in _EFFECT_BANDS:
            raise PolicyError(f"{where}.effect: expected allow, deny, barrier, or abstain")
        authority = raw.get("authority", "role")
        if not isinstance(authority, str) or authority not in {"invariant", "role", "spatial", "network", "default"}:
            raise PolicyError(f"{where}.authority: unsupported authority {authority!r}")
        if effect == "barrier" and authority != "invariant":
            raise PolicyError(f"{where}: barrier rules must come from authority 'invariant'")
        if authority == "invariant" and effect not in {"deny", "barrier"}:
            raise PolicyError(f"{where}: invariant authority may only deny or barrier")
        if effect == "abstain":
            # Abstention is a layer result, never a permission-granting rule.
            raise PolicyError(f"{where}: abstain is a layer result, not a storable rule effect")
        subject = raw.get("subject")
        if subject is not None and (not isinstance(subject, str) or not subject.strip()):
            raise PolicyError(f"{where}.subject: expected a stable, non-empty subject identifier")
        priority = raw.get("priority", 0)
        if not isinstance(priority, int) or isinstance(priority, bool) or not -(2**31) <= priority < 2**31:
            raise PolicyError(f"{where}.priority: expected a signed 32-bit integer")
        if not isinstance(raw.get("inherited", False), bool):
            raise PolicyError(f"{where}.inherited: expected a boolean")
        guard = raw.get("guard", {})
        if not isinstance(guard, dict):
            raise PolicyError(f"{where}.guard: expected an object")
        permitted_guards = {"world", "region", "game_mode", "persona", "min_health", "max_health"}
        unknown = {key for key in guard if key not in permitted_guards and not key.startswith("plugin:")}
        if unknown:
            raise PolicyError(f"{where}.guard: unsupported keys {sorted(unknown)}; schedule guards arrive with the spatial/time modules")
        if any(not key.removeprefix("plugin:") for key in guard if key.startswith("plugin:")):
            raise PolicyError(f"{where}.guard: plugin context keys must name a context field")
        if any(not isinstance(value, (str, int, float, bool, type(None)))
               for key, value in guard.items() if key.startswith("plugin:")):
            raise PolicyError(f"{where}.guard: plugin context values must be scalar JSON values")
        for name in ("world", "region", "game_mode", "persona"):
            val = guard.get(name)
            if val is not None and (not isinstance(val, str) or not val):
                raise PolicyError(f"{where}.guard.{name}: expected a non-empty string")
        for name in ("min_health", "max_health"):
            val = guard.get(name)
            if val is not None and (not isinstance(val, (int, float)) or isinstance(val, bool) or val < 0):
                raise PolicyError(f"{where}.guard.{name}: expected a non-negative number")
        if guard.get("min_health") is not None and guard.get("max_health") is not None and guard["min_health"] > guard["max_health"]:
            raise PolicyError(f"{where}.guard: min_health exceeds max_health")
        lifecycle = raw.get("lifecycle", {})
        if not isinstance(lifecycle, dict) or set(lifecycle) - {"valid_from", "expires_at", "budget"}:
            raise PolicyError(f"{where}.lifecycle: expected valid_from, expires_at, and/or budget")
        start = _parse_time(lifecycle.get("valid_from"), f"{where}.lifecycle.valid_from")
        expiry = _parse_time(lifecycle.get("expires_at"), f"{where}.lifecycle.expires_at")
        if start and expiry and expiry <= start:
            raise PolicyError(f"{where}.lifecycle: expires_at must be later than valid_from")
        budget = lifecycle.get("budget")
        if budget is not None:
            if not isinstance(budget, dict) or set(budget) != {"capacity", "period_seconds"}:
                raise PolicyError(f"{where}.lifecycle.budget: expected capacity and period_seconds")
            if any(not isinstance(budget[k], int) or isinstance(budget[k], bool) or budget[k] <= 0 for k in budget):
                raise PolicyError(f"{where}.lifecycle.budget: values must be positive integers (enforcement is Phase 2)")
            budget = MappingProxyType(dict(budget))
        guard = MappingProxyType(dict(guard))
        pattern = _compile_pattern(raw.get("permission"), f"{where}.permission")
        if authority == "default" and effect != "allow":
            raise PolicyError(f"{where}: namespace default currently supports allow rules only")
        if authority == "invariant":
            band = 0 if effect == "deny" else 1
        elif effect == "barrier":
            band = 1
        elif effect == "deny":
            band = 2
        elif authority == "default":
            band = 5
        elif authority == "role":
            band = 3 if raw.get("inherited", False) is False else 4
        elif effect == "allow":
            band = 4 if raw.get("inherited", False) else 3
        else:
            band = 3
        stable_key = json.dumps({"id": rid, "authority": authority, "effect": effect,
                                 "pattern": pattern.source, "subject": subject,
                                 "guard": dict(guard), "valid_from": start.isoformat() if start else None,
                                 "expires_at": expiry.isoformat() if expiry else None,
                                 "budget": dict(budget) if budget is not None else None},
                                sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        pending.append(dict(id=rid, pattern=pattern, effect=effect, band=band, authority=authority,
                            subject=subject, priority=priority, source_order=order, guard=guard,
                            valid_from=start, expires_at=expiry, budget=budget, stable_key=stable_key))

    # Heddle assigns ranks once. Warp's hot path compares integers; it never sorts.
    pending.sort(key=lambda x: (x["band"], -x["pattern"].specificity[0],
                                -x["pattern"].specificity[1], -x["pattern"].specificity[2], -x["priority"],
                                -x["source_order"], x["id"]))
    rules = tuple(Rule(**entry, rank=rank) for rank, entry in enumerate(pending))
    return CompiledPolicy(SCHEMA_VERSION, rules, source_name)
