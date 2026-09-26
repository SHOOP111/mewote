package io.lattice.warp;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Compiled dot-segment pattern. The only wildcards are '*' and terminal '**'. */
public final class ActionPattern {
    private final String source;
    private final String[] segments;
    private final int literalSegments;
    private final boolean recursive;

    private ActionPattern(String source, String[] segments, int literalSegments, boolean recursive) {
        this.source = source;
        this.segments = segments;
        this.literalSegments = literalSegments;
        this.recursive = recursive;
    }

    public static ActionPattern parse(String source) {
        Objects.requireNonNull(source, "source");
        if (source.isEmpty() || source.length() > 512 || !source.equals(source.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Pattern must be non-empty lowercase text: " + source);
        }
        String[] segments = source.split("\\.", -1);
        int literals = 0;
        boolean recursive = false;
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.equals("*")) {
                continue;
            }
            if (segment.equals("**")) {
                if (index != segments.length - 1) {
                    throw new IllegalArgumentException("'**' is only legal as the final segment: " + source);
                }
                recursive = true;
                continue;
            }
            if (!isLiteralSegment(segment)) {
                throw new IllegalArgumentException("Invalid pattern segment '" + segment + "' in " + source);
            }
            literals++;
        }
        return new ActionPattern(source, segments, literals, recursive);
    }

    static boolean isLiteralSegment(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-')) {
                return false;
            }
        }
        return true;
    }

    /** Matching uses only the precompiled segment arrays; it allocates no temporary strings. */
    public boolean matches(Action action) {
        int actionSize = action.segmentCount();
        int fixedSize = recursive ? segments.length - 1 : segments.length;
        if (recursive ? actionSize < fixedSize : actionSize != fixedSize) return false;
        for (int i = 0; i < fixedSize; i++) {
            String patternSegment = segments[i];
            if (!patternSegment.equals("*") && !patternSegment.equals(action.segmentAt(i))) return false;
        }
        return true;
    }

    /** Conservative proof that every action matched by this pattern is allowed by one ceiling pattern. */
    public boolean isSubsetOf(ActionPattern ceiling) {
        int ceilingFixed = ceiling.recursive ? ceiling.segments.length - 1 : ceiling.segments.length;
        int grantFixed = recursive ? segments.length - 1 : segments.length;

        if (!ceiling.recursive && (recursive || segments.length != ceiling.segments.length)) return false;
        if (ceiling.recursive && grantFixed < ceilingFixed) return false;

        int compareCount = ceiling.recursive ? ceilingFixed : ceiling.segments.length;
        for (int i = 0; i < compareCount; i++) {
            String cap = ceiling.segments[i];
            String grant = segments[i];
            if (cap.equals("*")) continue;
            if (grant.equals("*") || grant.equals("**") || !grant.equals(cap)) return false;
        }
        return true;
    }

    public String source() { return source; }
    public int segmentCount() { return segments.length; }
    public int literalSegments() { return literalSegments; }
    public boolean recursive() { return recursive; }
    public boolean broad() { return source.equals("**") || recursive && literalSegments <= 1; }
    public String firstSegment() { return segments[0]; }

    /** Compare by specificity only; negative means this pattern is more specific. */
    public int compareSpecificity(ActionPattern other) {
        int byLiterals = Integer.compare(other.literalSegments, literalSegments);
        if (byLiterals != 0) return byLiterals;
        int byExactness = Boolean.compare(recursive, other.recursive);
        if (byExactness != 0) return byExactness;
        return Integer.compare(other.segments.length, segments.length);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ActionPattern pattern && source.equals(pattern.source);
    }

    @Override
    public int hashCode() { return source.hashCode(); }

    @Override
    public String toString() { return source; }
}
