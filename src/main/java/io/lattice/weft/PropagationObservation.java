package io.lattice.weft;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Measurement helper for integration tests; it does not claim a network SLA by itself. */
public record PropagationObservation(Instant mutationAt, Instant appliedAt) {
    public PropagationObservation {
        Objects.requireNonNull(mutationAt, "mutationAt");
        Objects.requireNonNull(appliedAt, "appliedAt");
        if (appliedAt.isBefore(mutationAt)) throw new IllegalArgumentException("Applied time cannot precede mutation time");
    }
    public Duration latency() { return Duration.between(mutationAt, appliedAt); }
    public boolean meetsDeclaredBound() { return latency().compareTo(WeftMerger.DECLARED_PROPAGATION_BOUND) <= 0; }
}
