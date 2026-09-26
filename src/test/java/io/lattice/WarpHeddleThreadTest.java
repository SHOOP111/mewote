package io.lattice;

import io.lattice.heddle.PolicyCompileException;
import io.lattice.heddle.PolicyCompiler;
import io.lattice.selvedge.Selvedge;
import io.lattice.thread.Disclosure;
import io.lattice.thread.ExplanationLinkService;
import io.lattice.thread.ExplanationRenderer;
import io.lattice.warp.Action;
import io.lattice.warp.ActionPattern;
import io.lattice.warp.Authority;
import io.lattice.warp.Decision;
import io.lattice.warp.DecisionEngine;
import io.lattice.warp.DecisionRequest;
import io.lattice.warp.EvaluationContext;
import io.lattice.warp.ReasonCode;
import io.lattice.warp.RuleBand;
import io.lattice.warp.SubjectId;
import io.lattice.warp.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static io.lattice.TestSupport.NOW;
import static io.lattice.TestSupport.SUBJECT;
import static io.lattice.TestSupport.compile;
import static io.lattice.TestSupport.resource;
import static io.lattice.TestSupport.rule;
import static org.junit.jupiter.api.Assertions.*;

class WarpHeddleThreadTest {
    private static final String NO_ROLES = "{}";

    @Test void patternGrammarAndSegmentSemanticsAreExact() {
        ActionPattern one = ActionPattern.parse("minecraft.build.*");
        ActionPattern tail = ActionPattern.parse("minecraft.build.**");
        assertTrue(one.matches(Action.of("minecraft.build.place")));
        assertFalse(one.matches(Action.of("minecraft.build.region.set")));
        assertTrue(tail.matches(Action.of("minecraft.build")));
        assertTrue(tail.matches(Action.of("minecraft.build.region.set")));
        assertTrue(ActionPattern.parse("**").matches(Action.of("one.two.three")));
        assertTrue(ActionPattern.parse("minecraft.build.place").isSubsetOf(tail));
        assertFalse(tail.isSubsetOf(one));
        assertTrue(ActionPattern.parse("minecraft.build.**").broad());
    }

    @Test void wildcardInTheMiddleAndMalformedSegmentsAreCompileErrors() {
        for (String invalid : List.of("minecraft.**.build", "**.build", "minecraft.*.**.place", "minecraft..place", "minecraft.build.set*", "Minecraft.build")) {
            assertThrows(IllegalArgumentException.class, () -> ActionPattern.parse(invalid), invalid);
        }
    }

    @Test void survivalPackCompilesAndRoleClosureIsTransitive() throws Exception {
        var compilation = new PolicyCompiler().compile(resource("/policies/survival.json"));
        Set<String> effective = compilation.policy().roles().effectiveRoles(Set.of("builder"));
        assertEquals(Set.of("guest", "member", "builder"), effective);
        DecisionEngine engine = new DecisionEngine(compilation.policy());
        Decision allowed = engine.evaluate(new DecisionRequest(SUBJECT, null, null,
                Action.of("minecraft.build.place"), effective,
                new EvaluationContext("survival", "meadow", "healthy", "survival", "smp", java.util.Map.of()), NOW));
        assertEquals(Verdict.ALLOW, allowed.verdict());
        Decision barrier = engine.evaluate(new DecisionRequest(SUBJECT, null, null,
                Action.of("minecraft.build.place"), effective,
                new EvaluationContext("survival", "spawn", "healthy", "survival", "smp", java.util.Map.of()), NOW));
        assertEquals(Verdict.DENY, barrier.verdict());
        assertTrue(barrier.barrier());
        assertEquals(ReasonCode.BARRIER, barrier.reason());
    }

    @Test void bandsBarriersSpecificityAndSoftDenyHaveAStableTotalOrder() {
        String rules = String.join(",",
                rule("broad-deny", "minecraft.build.**", "DENY", "EXPLICIT", "NETWORK", ",\"priority\":100"),
                rule("specific-allow", "minecraft.build.place", "ALLOW", "EXPLICIT", "NETWORK", ",\"priority\":0"));
        DecisionEngine engine = new DecisionEngine(compile(NO_ROLES, rules).policy());
        Decision specific = engine.evaluate(DecisionRequest.forSubject(SUBJECT, "minecraft.build.place", Set.of(), EvaluationContext.empty(), NOW));
        assertEquals(Verdict.ALLOW, specific.verdict(), "more specific explicit rule overrides a broader soft deny");
        assertEquals("specific-allow", specific.ruleId());

        String equalSpecificity = String.join(",",
                rule("allow-high-priority", "minecraft.build.place", "ALLOW", "EXPLICIT", "NETWORK", ",\"priority\":2147483647"),
                rule("deny-low-priority", "minecraft.build.place", "DENY", "EXPLICIT", "NETWORK", ",\"priority\":-2147483648"));
        Decision tied = new DecisionEngine(compile(NO_ROLES, equalSpecificity).policy())
                .evaluate(DecisionRequest.forSubject(SUBJECT, "minecraft.build.place", Set.of(), EvaluationContext.empty(), NOW));
        assertEquals(Verdict.DENY, tied.verdict(), "at equal specificity and authority, deny precedes allow regardless of declared priority");
        assertEquals("deny-low-priority", tied.ruleId());

        String barrierPolicy = String.join(",",
                rule("barrier", "minecraft.build.place", "BARRIER", "BARRIER", "SPATIAL", ""),
                rule("allow", "minecraft.build.place", "ALLOW", "EXPLICIT", "NETWORK", ",\"priority\":1000"));
        Decision blocked = new DecisionEngine(compile(NO_ROLES, barrierPolicy).policy())
                .evaluate(DecisionRequest.forSubject(SUBJECT, "minecraft.build.place", Set.of(), EvaluationContext.empty(), NOW));
        assertEquals(Verdict.DENY, blocked.verdict());
        assertTrue(blocked.barrier());
    }

    @Test void totalOrderUsesPriorityThenLaterSourceOrderForPeers() {
        String rules = String.join(",",
                rule("first", "test.inspect", "ALLOW", "EXPLICIT", "PLUGIN", ",\"priority\":1"),
                rule("later-peer", "test.inspect", "ALLOW", "EXPLICIT", "PLUGIN", ",\"priority\":1"));
        Decision result = new DecisionEngine(compile(NO_ROLES, rules).policy())
                .evaluate(DecisionRequest.forSubject(SUBJECT, "test.inspect", Set.of(), EvaluationContext.empty(), NOW));
        assertEquals("later-peer", result.ruleId());
    }

    @Test void namespaceFallbackOnlyRunsAfterExplicitLayers() {
        String rules = String.join(",",
                rule("explicit", "game.play", "DENY", "EXPLICIT", "NETWORK", ""),
                rule("fallback", "game.**", "ALLOW", "NAMESPACE_DEFAULT", "NAMESPACE", ""));
        DecisionEngine engine = new DecisionEngine(compile(NO_ROLES, rules).policy());
        assertEquals(Verdict.DENY, engine.evaluate(DecisionRequest.forSubject(SUBJECT, "game.play", Set.of(), EvaluationContext.empty(), NOW)).verdict());
        assertEquals(Verdict.ALLOW, engine.evaluate(DecisionRequest.forSubject(SUBJECT, "game.build", Set.of(), EvaluationContext.empty(), NOW)).verdict());
    }

    @Test void declaredRoleOverrideSuppressesOnlyTheInheritedSoftRule() {
        String roles = "{\"parent\":{\"inherits\":[],\"ceiling\":[\"game.place\"]},"
                + "\"child\":{\"inherits\":[\"parent\"],\"ceiling\":[\"game.place\"],\"overrides\":[\"parent-deny\"]}}";
        String rules = String.join(",",
                rule("parent-deny", "game.place", "DENY", "EXPLICIT", "ROLE", ",\"role\":\"parent\""),
                rule("child-allow", "game.place", "ALLOW", "EXPLICIT", "ROLE", ",\"role\":\"child\""));
        DecisionEngine engine = new DecisionEngine(compile(roles, rules).policy());
        Set<String> closure = engine.expandRoles(Set.of("child"));
        Decision result = engine.evaluate(DecisionRequest.forSubject(SUBJECT, "game.place", closure, EvaluationContext.empty(), NOW));
        assertEquals(Verdict.ALLOW, result.verdict());
        assertTrue(result.record().trace().stream().anyMatch(step -> step.detail().equals("declared-role-override")));
    }

    @Test void compileRejectsCyclesCeilingEscapesAndUnknownFields() {
        String cycle = "{\"a\":{\"inherits\":[\"b\"],\"ceiling\":[\"game.**\"]},\"b\":{\"inherits\":[\"a\"],\"ceiling\":[\"game.**\"]}}";
        assertThrows(PolicyCompileException.class, () -> compile(cycle, ""));
        String tooWide = "{\"r\":{\"inherits\":[],\"ceiling\":[\"game.chat.*\"]}}";
        assertThrows(PolicyCompileException.class, () -> compile(tooWide,
                rule("bad", "game.**", "ALLOW", "EXPLICIT", "ROLE", ",\"role\":\"r\"")));
        String inheritedEscapeRoles = "{\"parent\":{\"inherits\":[],\"ceiling\":[\"game.**\"]},\"child\":{\"inherits\":[\"parent\"],\"ceiling\":[\"game.chat.**\"]}}";
        assertThrows(PolicyCompileException.class, () -> compile(inheritedEscapeRoles,
                rule("parent-admin", "game.admin.**", "ALLOW", "EXPLICIT", "ROLE", ",\"role\":\"parent\"")));
        String unknown = "{\"schemaVersion\":1,\"meta\":{\"id\":\"x\",\"version\":\"1\",\"description\":\"x\"},\"roles\":{},\"rules\":[],\"silentlyIgnored\":true}";
        var error = assertThrows(PolicyCompileException.class, () -> new PolicyCompiler().compile(unknown));
        assertEquals("UNKNOWN_FIELD", error.diagnostics().getFirst().code());
    }

    @Test void stableSubjectFieldsRejectDisplayNames() {
        String rules = rule("subject-rule", "game.play", "ALLOW", "EXPLICIT", "PLUGIN", ",\"subject\":\"Marcus\"");
        assertThrows(PolicyCompileException.class, () -> compile(NO_ROLES, rules));
        assertTrue(new Selvedge().approvalsSatisfySeparationOfDuties("java_uuid:a", "java_uuid:b"));
        assertFalse(new Selvedge().approvalsSatisfySeparationOfDuties("same", "same"));
    }

    @Test void scheduleUsesItsDeclaredZoneAndHalfOpenWindow() {
        String rules = rule("weekday", "game.deploy", "ALLOW", "EXPLICIT", "NETWORK",
                ",\"guard\":{\"schedule\":{\"zone\":\"America/New_York\",\"days\":[\"MONDAY\"],\"start\":\"09:00\",\"end\":\"10:00\"}}}");
        DecisionEngine engine = new DecisionEngine(compile(NO_ROLES, rules).policy());
        Decision inside = engine.evaluate(DecisionRequest.forSubject(SUBJECT, "game.deploy", Set.of(), EvaluationContext.empty(), Instant.parse("2026-03-09T13:30:00Z")));
        Decision atEnd = engine.evaluate(DecisionRequest.forSubject(SUBJECT, "game.deploy", Set.of(), EvaluationContext.empty(), Instant.parse("2026-03-09T14:00:00Z")));
        assertEquals(Verdict.ALLOW, inside.verdict(), "09:30 EDT is 13:30Z after the spring DST change");
        assertEquals(Verdict.DENY, atEnd.verdict(), "end is exclusive");
        assertTrue(atEnd.record().trace().stream().anyMatch(step -> step.detail().startsWith("OUTSIDE_SCHEDULE")));
    }

    @Test void overnightScheduleAttributesAfterMidnightToThePreviousConfiguredDay() {
        String rules = rule("overnight", "game.deploy", "ALLOW", "EXPLICIT", "NETWORK",
                ",\"guard\":{\"schedule\":{\"zone\":\"UTC\",\"days\":[\"MONDAY\"],\"start\":\"22:00\",\"end\":\"02:00\"}}}");
        DecisionEngine engine = new DecisionEngine(compile(NO_ROLES, rules).policy());
        assertEquals(Verdict.ALLOW, engine.evaluate(DecisionRequest.forSubject(SUBJECT, "game.deploy", Set.of(), EvaluationContext.empty(), Instant.parse("2026-01-13T01:30:00Z"))).verdict());
        assertEquals(Verdict.DENY, engine.evaluate(DecisionRequest.forSubject(SUBJECT, "game.deploy", Set.of(), EvaluationContext.empty(), Instant.parse("2026-01-13T02:00:00Z"))).verdict());
    }

    @Test void decisionRecordIsCanonicalAndDisclosureIsLeastPrivilege() {
        String rules = rule("internal.secret.rule", "minecraft.admin.delete", "DENY", "EXPLICIT", "NETWORK", "");
        DecisionEngine engine = new DecisionEngine(compile(NO_ROLES, rules).policy());
        DecisionRequest request = DecisionRequest.forSubject(SUBJECT, "minecraft.admin.delete", Set.of(), EvaluationContext.empty(), NOW);
        Decision first = engine.evaluate(request), second = engine.evaluate(request);
        assertEquals(first.record().canonical(), second.record().canonical());
        assertEquals(first.record().digest(), second.record().digest());
        ExplanationRenderer renderer = new ExplanationRenderer();
        var publicView = renderer.render(first, Disclosure.PUBLIC, "/request-access", Locale.ENGLISH);
        assertTrue(publicView.plainText().contains("Request access"));
        assertFalse(publicView.plainText().contains("internal.secret.rule"));
        assertFalse(publicView.plainText().contains(SUBJECT.toString()));
        assertTrue(publicView.severityLabel().contains("DENIED"));
        assertTrue(renderer.render(first, Disclosure.STAFF, null, Locale.ENGLISH).plainText().contains("internal.secret.rule"));
        assertTrue(renderer.render(first, Disclosure.AUDIT, null, Locale.ENGLISH).plainText().contains(first.record().digest()));
        assertTrue(renderer.render(first, Disclosure.PUBLIC, null, Locale.forLanguageTag("es")).plainText().contains("DENEGADO"));
    }

    @Test void explanationPermalinksAreScopedSignedAndExpiring() {
        Decision decision = new DecisionEngine(compile(NO_ROLES,
                rule("deny", "game.admin", "DENY", "EXPLICIT", "NETWORK", "")).policy())
                .evaluate(DecisionRequest.forSubject(SUBJECT, "game.admin", Set.of(), EvaluationContext.empty(), NOW));
        ExplanationLinkService links = new ExplanationLinkService(new byte[32]);
        String token = links.issue(decision.record(), ExplanationLinkService.Scope.PUBLIC, NOW.plusSeconds(60));
        var verified = links.verify(token, NOW.plusSeconds(1), decision.record().subjectId(), ExplanationLinkService.Scope.PUBLIC);
        assertEquals(decision.record().digest(), verified.recordDigest());
        assertThrows(SecurityException.class, () -> links.verify(token, NOW.plusSeconds(61), decision.record().subjectId(), ExplanationLinkService.Scope.AUDIT));
        assertThrows(SecurityException.class, () -> links.verify(token, NOW, "other-subject", ExplanationLinkService.Scope.AUDIT));
        char flip = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + flip;
        assertThrows(SecurityException.class, () -> links.verify(tampered, NOW, decision.record().subjectId(), ExplanationLinkService.Scope.AUDIT));
    }

    @Test void compiledInSelvedgeDeniesSelfEscalationAndCeilingMutation() {
        DecisionEngine engine = new DecisionEngine(compile(NO_ROLES, "").policy());
        DecisionRequest selfGrant = new DecisionRequest(SUBJECT, SUBJECT, SUBJECT, Action.of("lattice.policy.grant.role"), Set.of(), EvaluationContext.empty(), NOW);
        Decision self = engine.evaluate(selfGrant);
        assertEquals(Verdict.DENY, self.verdict());
        assertEquals(RuleBand.INVARIANT, self.band());
        assertEquals(ReasonCode.INVARIANT_SELF_ESCALATION, self.reason());
        Decision ceiling = engine.evaluate(DecisionRequest.forSubject(SUBJECT, "lattice.policy.ceiling.change", Set.of(), EvaluationContext.empty(), NOW));
        assertEquals(ReasonCode.INVARIANT_CEILING_MUTATION, ceiling.reason());
    }

    @Test void abstainingAuthorityIsNeutralAndMissingPolicyIsDeny() {
        assertEquals(Verdict.ALLOW, DecisionEngine.composeVerdict(Verdict.ALLOW, Verdict.ABSTAIN));
        assertEquals(Verdict.DENY, DecisionEngine.composeVerdict(Verdict.DENY, Verdict.ABSTAIN));
        Decision result = new DecisionEngine(compile(NO_ROLES, "").policy())
                .evaluate(DecisionRequest.forSubject(SUBJECT, "not.in.policy", Set.of(), EvaluationContext.empty(), NOW));
        assertEquals(Verdict.DENY, result.verdict());
        assertEquals(ReasonCode.DEFAULT_DENY, result.reason());
    }
}
