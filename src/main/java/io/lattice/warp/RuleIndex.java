package io.lattice.warp;

import io.lattice.thread.TraceStep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** First-segment index. Candidate arrays are ranked at compile time; resolution never sorts. */
public final class RuleIndex {
    private final Authority authority;
    private final Map<String, CompiledRule[]> literalFirst;
    private final CompiledRule[] wildcardFirst;

    RuleIndex(Authority authority, List<CompiledRule> source) {
        this.authority = authority;
        Map<String, List<CompiledRule>> literal = new HashMap<>();
        List<CompiledRule> wildcard = new ArrayList<>();
        for (CompiledRule compiled : source) {
            String first = compiled.rule().action().firstSegment();
            if (first.equals("*") || first.equals("**")) wildcard.add(compiled);
            else literal.computeIfAbsent(first, ignored -> new ArrayList<>()).add(compiled);
        }
        Comparator<CompiledRule> byRank = Comparator.comparingInt(CompiledRule::rank);
        literal.replaceAll((ignored, rules) -> {
            rules.sort(byRank);
            return List.copyOf(rules);
        });
        wildcard.sort(byRank);
        Map<String, CompiledRule[]> built = new HashMap<>();
        literal.forEach((key, rules) -> built.put(key, rules.toArray(CompiledRule[]::new)));
        this.literalFirst = Map.copyOf(built);
        this.wildcardFirst = wildcard.toArray(CompiledRule[]::new);
    }

    public LayerResolution resolve(DecisionRequest request) {
        CompiledRule[] literal = literalFirst.get(request.action().segmentAt(0));
        int i = 0;
        int j = 0;
        List<TraceStep> trace = new ArrayList<>();
        while ((literal != null && i < literal.length) || j < wildcardFirst.length) {
            CompiledRule candidate;
            if (literal == null || i >= literal.length) {
                candidate = wildcardFirst[j++];
            } else if (j >= wildcardFirst.length || literal[i].rank() < wildcardFirst[j].rank()) {
                candidate = literal[i++];
            } else {
                candidate = wildcardFirst[j++];
            }
            PolicyRule rule = candidate.rule();
            if (!rule.action().matches(request.action())) continue;
            if (candidate.isOverriddenFor(request)) {
                trace.add(step(rule, TraceStep.Outcome.SUBJECT_MISMATCH, "declared-role-override"));
                continue;
            }
            if (rule.subject() != null && !rule.subject().equals(request.subject())) {
                trace.add(step(rule, TraceStep.Outcome.SUBJECT_MISMATCH, "subject"));
                continue;
            }
            if (rule.role() != null && !request.effectiveRoles().contains(rule.role())) {
                trace.add(step(rule, TraceStep.Outcome.SUBJECT_MISMATCH, "role"));
                continue;
            }
            RuleGuard.Evaluation guard = rule.guard().evaluate(request.context(), request.at());
            if (!guard.matches()) {
                trace.add(step(rule, TraceStep.Outcome.GUARD_MISMATCH, guard.failure().name() + ":" + guard.key()));
                continue;
            }
            Verdict verdict = rule.effect() == RuleEffect.ALLOW ? Verdict.ALLOW : Verdict.DENY;
            ReasonCode reason = switch (rule.effect()) {
                case ALLOW -> ReasonCode.RULE_ALLOW;
                case DENY -> ReasonCode.RULE_DENY;
                case BARRIER -> ReasonCode.BARRIER;
            };
            trace.add(step(rule, TraceStep.Outcome.SELECTED, ""));
            return new LayerResolution(verdict, reason, candidate, trace);
        }
        return LayerResolution.abstain(trace);
    }

    private TraceStep step(PolicyRule rule, TraceStep.Outcome outcome, String detail) {
        return new TraceStep(rule.id(), rule.band(), authority, rule.effect(), outcome, detail);
    }

    public int indexedRuleCount() {
        int count = wildcardFirst.length;
        for (CompiledRule[] rules : literalFirst.values()) count += rules.length;
        return count;
    }
}
