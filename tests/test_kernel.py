from datetime import datetime, timezone
import random
import unittest

from lattice.compiler import PolicyError, compile_policy
from lattice.kernel import Context, Effect, evaluate

NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)


def policy(*rules):
    return compile_policy({"schema_version": 1, "rules": list(rules)})


def rule(rid, node, effect="allow", **extra):
    return {"id": rid, "permission": node, "effect": effect, **extra}


class PatternTests(unittest.TestCase):
    def test_single_and_trailing_multi_segment_wildcards(self):
        compiled = policy(rule("one", "worldedit.*"), rule("many", "server.admin.**"))
        ctx = Context("java:uuid", at=NOW)
        self.assertEqual(evaluate(compiled, "worldedit.clipboard", ctx).verdict, "allow")
        self.assertEqual(evaluate(compiled, "worldedit.clipboard.copy", ctx).verdict, "deny")
        self.assertEqual(evaluate(compiled, "server.admin", ctx).verdict, "allow")
        self.assertEqual(evaluate(compiled, "server.admin.reload", ctx).verdict, "allow")

    def test_middle_wildcard_is_compile_error(self):
        for node in ("a.**.b", "a.b*", "a.**.**"):
            with self.subTest(node=node), self.assertRaisesRegex(PolicyError, "wildcard"):
                policy(rule("bad", node))

    def test_invalid_empty_segments_are_rejected(self):
        with self.assertRaisesRegex(PolicyError, "empty permission segment"):
            policy(rule("bad", "server..stop"))


class OrderingTests(unittest.TestCase):
    def setUp(self):
        self.ctx = Context("java:001", at=NOW)

    def test_precedence_bands_are_strict(self):
        rules = [
            rule("default", "cmd.run", "allow", authority="default"),
            rule("inherited", "cmd.run", "allow", inherited=True),
            rule("grant", "cmd.run", "allow"),
            rule("deny", "cmd.run", "deny"),
            rule("barrier", "cmd.run", "barrier", authority="invariant"),
        ]
        for active in range(len(rules), 0, -1):
            result = evaluate(policy(*rules[:active]), "cmd.run", self.ctx)
            expected = ["barrier", "deny", "allow", "allow", "allow"][5-active]
            self.assertEqual(result.verdict, expected)
        # Invariant denial is stronger than an explicit barrier.
        result = evaluate(policy(rule("barrier", "cmd.run", "barrier", authority="invariant"),
                                 rule("invariant-deny", "cmd.run", "deny", authority="invariant")),
                          "cmd.run", self.ctx)
        self.assertEqual(result.verdict, "deny")

    def test_more_specific_precedes_priority_and_source_order(self):
        compiled = policy(rule("broad", "build.**", priority=999),
                          rule("exact", "build.place", priority=-999))
        self.assertEqual(evaluate(compiled, "build.place", self.ctx).rule_id, "exact")

    def test_single_segment_wildcard_is_more_specific_than_multi_tail(self):
        compiled = policy(rule("multi", "a.**"), rule("single", "a.*"))
        self.assertEqual(evaluate(compiled, "a.value", self.ctx).rule_id, "single")

    def test_priority_then_later_source_then_stable_id(self):
        compiled = policy(rule("first", "cmd.run", priority=2),
                          rule("later", "cmd.run", priority=2))
        self.assertEqual(evaluate(compiled, "cmd.run", self.ctx).rule_id, "later")
        same_order = policy(rule("zeta", "cmd.run", priority=2), rule("alpha", "cmd.run", priority=2))
        self.assertEqual(evaluate(same_order, "cmd.run", self.ctx).rule_id, "alpha")

    def test_barrier_cannot_be_overridden_by_grants(self):
        compiled = policy(rule("rail", "worldedit.**", "barrier", authority="invariant"),
                          rule("admin", "worldedit.*", "allow", priority=999999))
        self.assertEqual(evaluate(compiled, "worldedit.copy", self.ctx).verdict, "barrier")

    def test_authority_abstention_is_non_interference(self):
        compiled = policy(rule("grant", "cmd.run", authority="role"))
        active = evaluate(compiled, "cmd.run", self.ctx)
        abstaining = evaluate(compiled, "cmd.run", self.ctx, abstaining_authorities=frozenset({"spatial", "network"}))
        self.assertEqual(active.record, abstaining.record)

    def test_invariant_abstention_does_not_silence_other_authorities(self):
        compiled = policy(rule("grant", "cmd.run", authority="network"))
        result = evaluate(compiled, "cmd.run", self.ctx, abstaining_authorities=frozenset({"invariant"}))
        self.assertEqual(result.verdict, "allow")

    def test_independent_authority_order_is_input_order_independent(self):
        left = compile_policy({"schema_version": 1, "rules": [rule("shared", "cmd.run", "allow")]}, "left")
        right = compile_policy({"schema_version": 1, "rules": [rule("shared", "cmd.run", "deny")]}, "right")
        forward = evaluate((left, right), "cmd.run", self.ctx)
        reverse = evaluate((right, left), "cmd.run", self.ctx)
        self.assertEqual(forward.record, reverse.record)
        self.assertEqual(forward.verdict, "deny")


class GuardAndEvidenceTests(unittest.TestCase):
    def test_context_guards_and_expiry_are_fail_closed(self):
        compiled = policy(rule("spawn", "build.place", guard={"world": "spawn", "min_health": 5},
                               lifecycle={"valid_from": "2025-01-01T00:00:00Z", "expires_at": "2027-01-01T00:00:00Z"}))
        self.assertEqual(evaluate(compiled, "build.place", Context("id", world="spawn", health=10, at=NOW)).verdict, "allow")
        self.assertEqual(evaluate(compiled, "build.place", Context("id", world="wild", health=10, at=NOW)).verdict, "deny")
        self.assertEqual(evaluate(compiled, "build.place", Context("id", world="spawn", health=4, at=NOW)).verdict, "deny")
        expired = datetime(2028, 1, 1, tzinfo=timezone.utc)
        self.assertEqual(evaluate(compiled, "build.place", Context("id", world="spawn", health=10, at=expired)).verdict, "deny")

    def test_naive_times_rejected_and_expiry_is_exclusive(self):
        with self.assertRaisesRegex(PolicyError, "timezone"):
            policy(rule("bad", "x", lifecycle={"expires_at": "2026-01-01T00:00:00"}))
        compiled = policy(rule("expires", "x", lifecycle={"expires_at": "2026-01-01T00:00:00Z"}))
        self.assertEqual(evaluate(compiled, "x", Context("id", at=NOW)).verdict, "deny")

    def test_deterministic_record_and_digest(self):
        compiled = policy(rule("a", "cmd.run"))
        one = evaluate(compiled, "cmd.run", Context("stable-id", at=NOW))
        two = evaluate(compiled, "cmd.run", Context("stable-id", at=NOW))
        self.assertEqual(one.record, two.record)
        self.assertEqual(one.record_digest, two.record_digest)
        with self.assertRaises(TypeError):
            one.record["verdict"] = "allow"

    def test_plugin_context_is_explicit_and_fails_closed(self):
        compiled = policy(rule("plugin.guard", "server.command", guard={"plugin:combat.pvp": True}))
        yes = Context("stable-id", at=NOW, attributes={"plugin:combat.pvp": True})
        no = Context("stable-id", at=NOW, attributes={"plugin:combat.pvp": False})
        self.assertEqual(evaluate(compiled, "server.command", yes).verdict, "allow")
        self.assertEqual(evaluate(compiled, "server.command", no).verdict, "deny")
        with self.assertRaisesRegex(PolicyError, "schedule guards"):
            policy(rule("schedule", "server.command", guard={"time": {"start": "09:00"}}))

    def test_public_explanation_does_not_disclose_rule_details(self):
        decision = evaluate(policy(rule("secret.internal.source", "admin.**", "deny")), 
                            "admin.reset", Context("id", at=NOW))
        public = decision.explain("public")
        self.assertIn("request access", public)
        self.assertNotIn("secret", public)
        self.assertNotIn("admin.reset", public)
        self.assertIn("secret.internal.source", decision.explain("staff"))


class GeneratedLawTests(unittest.TestCase):
    def test_random_patterns_are_deterministic_and_specific_rules_win(self):
        rng = random.Random(78123)
        for case in range(1200):
            tail = rng.choice(["read", "write", "delete"])
            exact = f"plugin.{rng.randrange(20)}.{tail}"
            broad = f"plugin.{exact.split('.')[1]}.**"
            docs = [rule(f"broad-{case}", broad, "allow", priority=rng.randrange(1000)),
                    rule(f"exact-{case}", exact, "allow", priority=-rng.randrange(1000))]
            compiled = policy(*docs)
            context = Context(f"offline:{case}", at=NOW)
            first = evaluate(compiled, exact, context)
            second = evaluate(compiled, exact, context)
            self.assertEqual(first.record, second.record)
            self.assertEqual(first.verdict, "allow")
            self.assertEqual(first.rule_id, f"exact-{case}")

    def test_policy_rejects_duplicate_ids_and_invalid_lifecycle(self):
        with self.assertRaisesRegex(PolicyError, "duplicate rule id"):
            policy(rule("same", "a"), rule("same", "b"))
        with self.assertRaisesRegex(PolicyError, "later than"):
            policy(rule("bad", "a", lifecycle={"valid_from": "2026-02-01T00:00:00Z",
                                                 "expires_at": "2026-01-01T00:00:00Z"}))


if __name__ == "__main__":
    unittest.main()
