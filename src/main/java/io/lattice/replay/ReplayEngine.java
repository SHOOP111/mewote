package io.lattice.replay;

import io.lattice.warp.Decision;
import io.lattice.warp.DecisionEngine;
import io.lattice.warp.DecisionRequest;
import io.lattice.warp.SubjectId;
import io.lattice.warp.Verdict;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Deterministic policy-diff runner over captured requests. Candidate decisions use isolated budgets. */
public final class ReplayEngine {
    public record RecordedQuery(DecisionRequest request, Verdict liveVerdict, String liveRuleId) { }
    public record Difference(SubjectId subject, String action, Verdict before, Verdict after,
                             String beforeRule, String afterRule) { }
    public record Report(int queries, int verdictChanges, Map<String, Integer> transitions,
                         List<SubjectId> affectedSubjects, List<Difference> differences,
                         int explanationOnlyChanges) {
        public Report {
            transitions = java.util.Collections.unmodifiableMap(new TreeMap<>(transitions));
            affectedSubjects = List.copyOf(affectedSubjects);
            differences = List.copyOf(differences);
        }
    }

    public Report replay(List<RecordedQuery> traffic, DecisionEngine candidate) {
        List<Difference> differences = new ArrayList<>();
        TreeSet<SubjectId> subjects = new TreeSet<>();
        Map<String, Integer> transitions = new TreeMap<>();
        int explanationOnly = 0;
        for (RecordedQuery query : traffic) {
            Decision after = candidate.evaluate(query.request());
            if (after.verdict() != query.liveVerdict()) {
                differences.add(new Difference(query.request().subject(), query.request().action().value(),
                        query.liveVerdict(), after.verdict(), query.liveRuleId(), after.ruleId()));
                subjects.add(query.request().subject());
                String transition = query.liveVerdict() + "->" + after.verdict();
                transitions.merge(transition, 1, Integer::sum);
            } else if (!java.util.Objects.equals(query.liveRuleId(), after.ruleId())) {
                explanationOnly++;
            }
        }
        return new Report(traffic.size(), differences.size(), transitions, new ArrayList<>(subjects), differences, explanationOnly);
    }

    public boolean cutoverEligible(Report report, boolean explicitlyAcceptDivergence) {
        return report.verdictChanges() == 0 || explicitlyAcceptDivergence;
    }
}
