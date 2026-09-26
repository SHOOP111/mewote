package io.lattice.weft;

import java.util.Objects;

/** Hybrid logical timestamp; node ID completes a deterministic total order on ties. */
public record HlcTimestamp(long physicalMillis, int logical, String nodeId) implements Comparable<HlcTimestamp> {
    public HlcTimestamp {
        if (logical < 0) throw new IllegalArgumentException("logical counter cannot be negative");
        Objects.requireNonNull(nodeId, "nodeId");
        if (nodeId.isBlank()) throw new IllegalArgumentException("nodeId is required");
    }
    @Override public int compareTo(HlcTimestamp other) {
        int result = Long.compare(physicalMillis, other.physicalMillis);
        if (result == 0) result = Integer.compare(logical, other.logical);
        if (result == 0) result = nodeId.compareTo(other.nodeId);
        return result;
    }
}
