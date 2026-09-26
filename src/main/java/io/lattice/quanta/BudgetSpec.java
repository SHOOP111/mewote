package io.lattice.quanta;

import java.time.Duration;
import java.util.Objects;

/** Declarative token bucket policy. Refill periods are based on monotonic elapsed time. */
public record BudgetSpec(int capacity, int refillTokens, Duration refillPeriod) {
    public BudgetSpec {
        if (capacity < 1) throw new IllegalArgumentException("Budget capacity must be positive");
        if (refillTokens < 1) throw new IllegalArgumentException("Budget refillTokens must be positive");
        Objects.requireNonNull(refillPeriod, "refillPeriod");
        if (refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("Budget refillPeriod must be positive");
        }
    }
}
