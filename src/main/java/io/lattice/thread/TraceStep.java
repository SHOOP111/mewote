package io.lattice.thread;

import io.lattice.warp.Authority;
import io.lattice.warp.RuleBand;
import io.lattice.warp.RuleEffect;

import java.util.Objects;

/** Compact machine evidence for one candidate encountered by Warp. */
public record TraceStep(String ruleId, RuleBand band, Authority authority, RuleEffect effect,
                        Outcome outcome, String detail) {
    public enum Outcome { SUBJECT_MISMATCH, GUARD_MISMATCH, SELECTED, LOWER_PRECEDENCE, INVARIANT }

    public TraceStep {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(outcome, "outcome");
        detail = detail == null ? "" : detail;
    }
}
