package io.lattice.warp;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Stable identity. Display names deliberately have no constructor here. */
public record SubjectId(Kind kind, String canonical) implements Comparable<SubjectId> {
    public enum Kind { JAVA_UUID, BEDROCK_XUID, SERVICE_ACCOUNT }

    public SubjectId {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(canonical, "canonical");
        canonical = canonical.trim();
        if (canonical.isEmpty()) throw new IllegalArgumentException("Stable subject identifier is required");
        if (kind == Kind.JAVA_UUID) canonical = UUID.fromString(canonical).toString();
        if (kind == Kind.BEDROCK_XUID && !canonical.matches("[0-9]{1,32}")) {
            throw new IllegalArgumentException("Bedrock XUID must be a decimal stable identifier");
        }
        if (kind == Kind.SERVICE_ACCOUNT && !canonical.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid service account identifier");
        }
    }

    public static SubjectId java(UUID uuid) { return new SubjectId(Kind.JAVA_UUID, uuid.toString()); }
    public static SubjectId bedrock(String xuid) { return new SubjectId(Kind.BEDROCK_XUID, xuid); }
    public static SubjectId service(String id) { return new SubjectId(Kind.SERVICE_ACCOUNT, id.toLowerCase(Locale.ROOT)); }

    @Override
    public int compareTo(SubjectId other) {
        int byKind = kind.compareTo(other.kind);
        return byKind != 0 ? byKind : canonical.compareTo(other.canonical);
    }

    @Override public String toString() { return kind.name().toLowerCase(Locale.ROOT) + ":" + canonical; }
}
