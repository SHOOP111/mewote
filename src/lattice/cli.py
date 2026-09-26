"""Command line surface for the Phase 0 vertical slice."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
import sys

from .compiler import PolicyError, compile_policy, lint_policy, load_policy
from .kernel import Context, evaluate

PACKS = Path(__file__).resolve().parents[2] / "policies" / "packs"


def _context(args: argparse.Namespace) -> Context:
    at = datetime.fromisoformat(args.at.replace("Z", "+00:00")) if args.at else datetime.now(timezone.utc)
    return Context(subject=args.subject, world=args.world, region=args.region,
                   game_mode=args.game_mode, persona=args.persona, health=args.health, at=at)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="lat", description="LATTICE — inspectable authority decisions")
    sub = parser.add_subparsers(dest="command", required=True)
    init = sub.add_parser("init", help="install a curated starter policy pack")
    init.add_argument("--pack", choices=["survival"], required=True)
    init.add_argument("--path", default="lattice-policy.json")
    lint = sub.add_parser("lint", help="validate and lint a policy")
    lint.add_argument("policy")
    for command in ("check", "explain"):
        item = sub.add_parser(command, help=f"{command} one permission decision")
        item.add_argument("policy")
        item.add_argument("permission")
        item.add_argument("--subject", default="offline:example-player")
        item.add_argument("--world")
        item.add_argument("--region")
        item.add_argument("--game-mode")
        item.add_argument("--persona")
        item.add_argument("--health", type=float)
        item.add_argument("--at", help="ISO-8601 timestamp with timezone")
        if command == "explain":
            item.add_argument("--disclosure", choices=["public", "staff", "audit"], default="staff")
    replay = sub.add_parser("simulate", help="compare policies against JSONL permission traffic")
    replay.add_argument("baseline")
    replay.add_argument("candidate")
    replay.add_argument("traffic", help="JSONL with permission and context fields")
    replay.add_argument("--show", type=int, default=20, help="maximum differing records to print")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.command == "init":
            source = PACKS / args.pack / "policy.json"
            target = Path(args.path)
            if target.exists():
                raise PolicyError(f"{target}: refusing to overwrite an existing policy")
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            print(f"Installed {args.pack} starter policy at {target}")
            print("Next: lat lint " + str(target))
            return 0
        if args.command == "lint":
            document = load_policy(args.policy)
            compile_policy(document, args.policy)
            warnings = lint_policy(document)
            if warnings:
                for warning in warnings:
                    print(f"WARN  {warning}")
                print(f"Lint complete: {len(warnings)} warning(s); policy is structurally valid.")
            else:
                print("Lint complete: no warnings.")
            return 0
        if args.command in {"check", "explain"}:
            policy = compile_policy(load_policy(args.policy), args.policy)
            decision = evaluate(policy, args.permission, _context(args))
            if args.command == "check":
                print(decision.verdict.upper())
                return 0 if decision.verdict == "allow" else 1
            print(decision.explain(args.disclosure))
            if args.disclosure == "audit":
                print("sha256:" + decision.record_digest)
            return 0
        if args.command == "simulate":
            baseline = compile_policy(load_policy(args.baseline), args.baseline)
            candidate = compile_policy(load_policy(args.candidate), args.candidate)
            differences = total = 0
            with open(args.traffic, encoding="utf-8") as traffic:
                for line_number, line in enumerate(traffic, 1):
                    if not line.strip():
                        continue
                    try:
                        row = json.loads(line)
                        permission = row.pop("permission")
                        if isinstance(row.get("at"), str):
                            row["at"] = datetime.fromisoformat(row["at"].replace("Z", "+00:00"))
                        context = Context(**row)
                    except (ValueError, TypeError, KeyError) as exc:
                        raise PolicyError(f"{args.traffic}:{line_number}: invalid traffic record: {exc}") from exc
                    left = evaluate(baseline, permission, context)
                    right = evaluate(candidate, permission, context)
                    total += 1
                    if left.verdict != right.verdict:
                        differences += 1
                        if differences <= args.show:
                            print(json.dumps({"line": line_number, "permission": permission,
                                              "subject": context.subject, "before": left.verdict,
                                              "after": right.verdict}, sort_keys=True))
            print(f"Replay complete: {differences}/{total} decisions differ.")
            return 2 if differences else 0
    except (PolicyError, OSError, ValueError) as exc:
        print(f"ERROR {exc}", file=sys.stderr)
        return 2
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
