package io.lattice.selvedge;

import io.lattice.thread.TraceStep;
import io.lattice.warp.Authority;
import io.lattice.warp.DecisionRequest;
import io.lattice.warp.ReasonCode;
import io.lattice.warp.RuleBand;
import io.lattice.warp.RuleEffect;
import io.lattice.warp.Verdict;

import java.util.Optional;

/** Compiled-in mutation rails. Dynamic policy cannot disable or shadow these checks. */
public final class Selvedge {
    public record Finding(String id, ReasonCode reason, TraceStep evidence) { }

    public Optional<Finding> inspect(DecisionRequest request) {
        String action = request.action().value();
        if (action.equals("lattice.policy.ceiling.change") || action.startsWith("lattice.policy.ceiling.change.")) {
            return Optional.of(finding("selvedge.ceiling.immutable", ReasonCode.INVARIANT_CEILING_MUTATION));
        }
        if (action.equals("lattice.policy.grant") || action.startsWith("lattice.policy.grant.")) {
            var actor = request.actor();
            var target = request.target();
            if (actor == null || target == null || actor.equals(target)) {
                return Optional.of(finding("selvedge.no-self-escalation", ReasonCode.INVARIANT_SELF_ESCALATION));
            }
        }
        return Optional.empty();
    }

    private Finding finding(String id, ReasonCode reason) {
        return new Finding(id, reason, new TraceStep(id, RuleBand.INVARIANT, Authority.SELVEDGE,
                RuleEffect.DENY, TraceStep.Outcome.INVARIANT, reason.name()));
    }

    /** Approval split required for sensitive changes. Requester and approver are stable IDs. */
    public boolean approvalsSatisfySeparationOfDuties(String requester, String approver) {
        return requester != null && approver != null && !requester.equals(approver);
    }
}
