package io.lattice.weft;

import io.lattice.warp.ActionPattern;
import io.lattice.warp.SubjectId;

import java.util.Objects;

public record Mutation(String eventId, SubjectId subject, ActionPattern capability, Kind kind,
                       HlcTimestamp clock, boolean approvedRegrant) {
    public enum Kind { GRANT, REVOKE }
    public Mutation {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(clock, "clock");
        if (eventId.isBlank()) throw new IllegalArgumentException("eventId is required");
        if (kind == Kind.REVOKE && approvedRegrant) throw new IllegalArgumentException("A revoke cannot be marked as an approved regrant");
    }
    public String resourceKey() { return subject + "|" + capability.source(); }
}
