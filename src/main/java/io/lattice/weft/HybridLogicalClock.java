package io.lattice.weft;

import java.time.Instant;
import java.util.Objects;

/** Thread-safe HLC. Wall-clock regressions never move this node's logical time backwards. */
public final class HybridLogicalClock {
    private final String nodeId;
    private HlcTimestamp last;

    public HybridLogicalClock(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId is required");
        this.nodeId = nodeId;
        this.last = new HlcTimestamp(Long.MIN_VALUE, 0, nodeId);
    }

    public synchronized HlcTimestamp tick(Instant wallTime) {
        long wall = Objects.requireNonNull(wallTime, "wallTime").toEpochMilli();
        if (wall > last.physicalMillis()) last = new HlcTimestamp(wall, 0, nodeId);
        else last = new HlcTimestamp(last.physicalMillis(), increment(last.logical()), nodeId);
        return last;
    }

    public synchronized HlcTimestamp receive(HlcTimestamp remote, Instant wallTime) {
        Objects.requireNonNull(remote, "remote");
        long wall = Objects.requireNonNull(wallTime, "wallTime").toEpochMilli();
        long physical = Math.max(wall, Math.max(last.physicalMillis(), remote.physicalMillis()));
        int logical;
        if (physical == last.physicalMillis() && physical == remote.physicalMillis()) {
            logical = increment(Math.max(last.logical(), remote.logical()));
        } else if (physical == last.physicalMillis()) {
            logical = increment(last.logical());
        } else if (physical == remote.physicalMillis()) {
            logical = increment(remote.logical());
        } else {
            logical = 0;
        }
        last = new HlcTimestamp(physical, logical, nodeId);
        return last;
    }

    public synchronized HlcTimestamp current() { return last; }
    private static int increment(int value) {
        if (value == Integer.MAX_VALUE) throw new IllegalStateException("HLC logical counter exhausted; clock cannot safely wrap");
        return value + 1;
    }
}
