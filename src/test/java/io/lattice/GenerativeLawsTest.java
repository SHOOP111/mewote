package io.lattice;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import io.lattice.warp.Action;
import io.lattice.warp.ActionPattern;
import io.lattice.warp.DecisionEngine;
import io.lattice.warp.DecisionRequest;
import io.lattice.warp.EvaluationContext;
import io.lattice.warp.Verdict;
import io.lattice.weft.HlcTimestamp;
import io.lattice.weft.Mutation;
import io.lattice.weft.WeftMerger;
import io.lattice.weft.WeftSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.lattice.TestSupport.NOW;
import static io.lattice.TestSupport.SUBJECT;
import static io.lattice.TestSupport.compile;
import static org.junit.jupiter.api.Assertions.*;

/** Generative laws. jqwik shrinks failing lists, segments, priorities, and counters to a smaller case. */
class GenerativeLawsTest {
    private record RuleCase(String action, String effect, int priority) { }
    private record MutationCase(String id, Mutation.Kind kind, long clock, boolean approved) { }

    @Provide Arbitrary<String> matchingPatterns() {
        return Arbitraries.of("**", "test.**", "test.node", "test.*");
    }

    @Provide Arbitrary<String> actionPatterns() {
        return Arbitraries.of("**", "test.**", "test.node", "test.*", "minecraft.build.place", "minecraft.build.**", "plugin.alpha.*");
    }

    @Provide Arbitrary<RuleCase> ruleCases() {
        return Combinators.combine(actionPatterns(), Arbitraries.of("ALLOW", "DENY"), Arbitraries.integers().between(-100, 100))
                .as(RuleCase::new);
    }

    @Provide Arbitrary<List<RuleCase>> generatedPolicies() {
        return ruleCases().list().ofMinSize(0).ofMaxSize(24);
    }

    @Provide Arbitrary<Integer> chainLengths() {
        return Arbitraries.integers().between(1, 12);
    }

    @Provide Arbitrary<List<MutationCase>> mutationSets() {
        Arbitrary<MutationCase> mutation = Combinators.combine(
                Arbitraries.strings().withChars('a', 'b', 'c', 'd', 'e', 'f', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9').ofLength(8),
                Arbitraries.of(Mutation.Kind.GRANT, Mutation.Kind.REVOKE),
                Arbitraries.longs().between(0, 1_000_000),
                Arbitraries.of(true, false))
                .as(MutationCase::new);
        return mutation.list().ofMinSize(0).ofMaxSize(40);
    }

    @Property(tries = 1500)
    void barriersAreAbsoluteForEveryMatchingPolicyShape(@ForAll("matchingPatterns") String pattern,
                                                        @ForAll int priority) {
        String rules = "{\"id\":\"hard\",\"action\":\"" + pattern + "\",\"effect\":\"BARRIER\",\"band\":\"BARRIER\",\"authority\":\"SPATIAL\",\"priority\":" + priority + "},"
                + "{\"id\":\"soft\",\"action\":\"" + pattern + "\",\"effect\":\"ALLOW\",\"band\":\"EXPLICIT\",\"authority\":\"NETWORK\",\"priority\":" + (-priority) + "}";
        DecisionEngine engine = new DecisionEngine(compile("{}", rules).policy());
        var decision = engine.evaluate(DecisionRequest.forSubject(SUBJECT, "test.node", Set.of(), EvaluationContext.empty(), NOW));
        assertEquals(Verdict.DENY, decision.verdict());
        assertTrue(decision.barrier());
    }

    @Property(tries = 2000)
    void sameCompiledStateAndQuestionProducesByteIdenticalEvidence(@ForAll("generatedPolicies") List<RuleCase> cases) {
        StringBuilder rules = new StringBuilder();
        for (int i = 0; i < cases.size(); i++) {
            if (i > 0) rules.append(',');
            RuleCase item = cases.get(i);
            rules.append("{\"id\":\"r").append(i).append("\",\"action\":\"").append(item.action())
                    .append("\",\"effect\":\"").append(item.effect())
                    .append("\",\"band\":\"EXPLICIT\",\"authority\":\"NETWORK\",\"priority\":")
                    .append(item.priority()).append('}');
        }
        DecisionEngine engine = new DecisionEngine(compile("{}", rules.toString()).policy());
        DecisionRequest request = DecisionRequest.forSubject(SUBJECT, "test.node", Set.of(), EvaluationContext.empty(), NOW);
        String first = engine.evaluate(request).record().canonical();
        String second = engine.evaluate(request).record().canonical();
        assertEquals(first, second);
    }

    @Property(tries = 1000)
    void roleInheritanceIsTransitiveForEveryAcyclicChain(@ForAll("chainLengths") int length) {
        StringBuilder roles = new StringBuilder("{");
        for (int i = 0; i < length; i++) {
            if (i > 0) roles.append(',');
            roles.append("\"role").append(i).append("\":{\"inherits\":[");
            if (i > 0) roles.append("\"role").append(i - 1).append("\"");
            roles.append("],\"ceiling\":[\"game.**\"]}");
        }
        roles.append('}');
        var policy = compile(roles.toString(), "").policy();
        Set<String> closure = policy.roles().effectiveRoles(Set.of("role" + (length - 1)));
        assertEquals(length, closure.size());
        for (int i = 0; i < length; i++) assertTrue(closure.contains("role" + i));
    }

    @Property(tries = 1500)
    void distributedMergeIsIndependentOfMessageArrivalOrder(@ForAll("mutationSets") List<MutationCase> input) {
        List<Mutation> mutations = new ArrayList<>();
        for (MutationCase item : input) {
            mutations.add(new Mutation(item.id(), SUBJECT, ActionPattern.parse("game.build.place"), item.kind(),
                    new HlcTimestamp(item.clock(), 0, "node"), item.kind() == Mutation.Kind.GRANT && item.approved()));
        }
        WeftMerger merger = new WeftMerger();
        WeftSnapshot empty = new WeftSnapshot(java.util.Map.of(), java.util.Map.of());
        var forward = merger.merge(empty, mutations, ignored -> true);
        List<Mutation> reversed = new ArrayList<>(mutations);
        java.util.Collections.reverse(reversed);
        var backward = merger.merge(empty, reversed, ignored -> true);
        assertEquals(forward.status(), backward.status());
        assertEquals(forward.snapshot().events(), backward.snapshot().events());
        assertEquals(forward.snapshot().effective(), backward.snapshot().effective());
    }
}
