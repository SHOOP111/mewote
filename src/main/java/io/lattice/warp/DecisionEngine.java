package io.lattice.warp;

import io.lattice.quanta.BudgetService;
import io.lattice.quanta.Quanta;
import io.lattice.quanta.BudgetService.Reservation;
import io.lattice.selvedge.Selvedge;
import io.lattice.thread.ReasoningRecord;
import io.lattice.thread.TraceStep;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Warp's immutable-policy decision kernel. */
public final class DecisionEngine {
    private final CompiledPolicy policy;
    private final Selvedge selvedge;
    private final Quanta quanta;

    public DecisionEngine(CompiledPolicy policy) {
        this(policy, new Selvedge(), new Quanta(new BudgetService()));
    }

    public DecisionEngine(CompiledPolicy policy, Selvedge selvedge, Quanta quanta) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.selvedge = Objects.requireNonNull(selvedge, "selvedge");
        this.quanta = Objects.requireNonNull(quanta, "quanta");
    }

    /** Resolve each authority independently, then compose non-abstaining outcomes by compiled rank. */
    public Decision evaluate(DecisionRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<Selvedge.Finding> invariant = selvedge.inspect(request);
        if (invariant.isPresent()) {
            Selvedge.Finding finding = invariant.get();
            return decision(request, Verdict.DENY, finding.reason(), RuleBand.INVARIANT, finding.id(),
                    false, null, List.of(finding.evidence()), null);
        }

        List<AuthorityResolution> outcomes = new ArrayList<>();
        for (Authority authority : Authority.values()) {
            if (authority == Authority.SELVEDGE) continue;
            LayerResolution layer = resolveAuthority(authority, request);
            outcomes.add(new AuthorityResolution(authority, layer));
        }

        AuthorityResolution winner = outcomes.stream()
                .filter(outcome -> outcome.layer().verdict() != Verdict.ABSTAIN)
                .min(Comparator.comparingInt(outcome -> outcome.layer().winner().rank()))
                .orElse(null);
        List<TraceStep> evidence = new ArrayList<>();
        for (AuthorityResolution outcome : outcomes) {
            for (TraceStep step : outcome.layer().trace()) {
                if (step.outcome() == TraceStep.Outcome.SELECTED
                        && (winner == null || outcome.layer().winner() != winner.layer().winner())) {
                    evidence.add(new TraceStep(step.ruleId(), step.band(), step.authority(), step.effect(),
                            TraceStep.Outcome.LOWER_PRECEDENCE, "another authority had an earlier compiled rank"));
                } else {
                    evidence.add(step);
                }
            }
        }
        if (winner == null) {
            return decision(request, Verdict.DENY, ReasonCode.DEFAULT_DENY, null, null, false, null, evidence, null);
        }

        PolicyRule rule = winner.layer().winner().rule();
        BudgetService.Reservation budgetReservation = null;
        if (winner.layer().verdict() == Verdict.ALLOW && rule.budget() != null) {
            budgetReservation = quanta.budgets().reserve(rule.id(), request.subject(), scope(request), rule.budget());
            if (!budgetReservation.reserved()) {
                evidence.add(new TraceStep(rule.id(), rule.band(), rule.authority(), rule.effect(),
                        TraceStep.Outcome.GUARD_MISMATCH, "BUDGET_EXHAUSTED"));
                return decision(request, Verdict.DENY, ReasonCode.BUDGET_EXHAUSTED, rule.band(), rule.id(),
                        false, budgetReservation.retryAfter(), evidence, null);
            }
        }
        return decision(request, winner.layer().verdict(), winner.layer().reason(), rule.band(), rule.id(),
                rule.effect() == RuleEffect.BARRIER, null, evidence,
                budgetReservation == null ? null : budgetReservation.lease());
    }

    public LayerResolution resolveAuthority(Authority authority, DecisionRequest request) {
        if (authority == Authority.SELVEDGE) throw new IllegalArgumentException("Selvedge is not policy-authored; inspect it before dynamic layers.");
        RuleIndex index = policy.index(authority);
        return index.resolve(request);
    }

    /** Abstention is neutral by definition; it can never replace an outer verdict. */
    public static Verdict composeVerdict(Verdict outer, Verdict layer) {
        return layer == Verdict.ABSTAIN ? outer : layer;
    }

    /** Role closure is a write-time identity operation; callers cache its result for checks. */
    public java.util.Set<String> expandRoles(java.util.Set<String> directRoles) {
        return policy.roles().effectiveRoles(directRoles);
    }

    private Decision decision(DecisionRequest request, Verdict verdict, ReasonCode reason, RuleBand band,
                              String ruleId, boolean barrier, Duration retryAfter, List<TraceStep> trace,
                              io.lattice.quanta.BudgetLease lease) {
        ReasoningRecord record = new ReasoningRecord(policy.policyHash(), request.subject().toString(),
                request.action().value(), verdict, reason, band, ruleId, barrier, trace);
        return new Decision(verdict, reason, band, ruleId, barrier, retryAfter, record, lease);
    }

    private static String scope(DecisionRequest request) {
        if (request.context().region() != null) return "region:" + request.context().region();
        if (request.context().world() != null) return "world:" + request.context().world();
        return "network";
    }

    private record AuthorityResolution(Authority authority, LayerResolution layer) { }
}
