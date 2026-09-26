package io.lattice.warp;

import io.lattice.quanta.BudgetLease;
import io.lattice.thread.ReasoningRecord;

import java.time.Duration;
import java.util.Objects;

/** Verdict, evidence, and (for a budgeted allow) its atomic reservation are returned together. */
public record Decision(Verdict verdict, ReasonCode reason, RuleBand band, String ruleId,
                       boolean barrier, Duration retryAfter, ReasoningRecord record, BudgetLease lease) {
    public Decision {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(record, "record");
        if (verdict == Verdict.ABSTAIN && (ruleId != null || lease != null)) {
            throw new IllegalArgumentException("An abstention cannot carry a winning rule or lease");
        }
        if (retryAfter != null && retryAfter.isNegative()) throw new IllegalArgumentException("retryAfter cannot be negative");
    }

    public boolean allowed() { return verdict == Verdict.ALLOW; }
}
