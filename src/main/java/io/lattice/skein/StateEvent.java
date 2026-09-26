package io.lattice.skein;

import io.lattice.weft.HlcTimestamp;
import io.lattice.warp.SubjectId;

import java.util.Objects;

public record StateEvent(long sequence, HlcTimestamp clock, SubjectId subject, String key,
                         Operation operation, SealedPayload payload, String previousHash, String hash) {
    public enum Operation { SET, DELETE }
    public StateEvent {
        if (sequence < 1) throw new IllegalArgumentException("sequence starts at one");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(previousHash, "previousHash");
        Objects.requireNonNull(hash, "hash");
        if (operation == Operation.SET && payload == null) throw new IllegalArgumentException("SET event requires an encrypted payload");
        if (operation == Operation.DELETE && payload != null) throw new IllegalArgumentException("DELETE event cannot carry a payload");
    }
}
