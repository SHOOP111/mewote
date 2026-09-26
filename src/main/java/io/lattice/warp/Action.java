package io.lattice.warp;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** A canonical, pre-segmented permission node. Construct at an API boundary, not per hot-path check. */
public final class Action {
    private final String value;
    private final String[] segments;

    private Action(String value, String[] segments) {
        this.value = value;
        this.segments = segments;
    }

    public static Action of(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || value.length() > 512 || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Action must be non-empty lowercase text: " + value);
        }
        String[] segments = value.split("\\.", -1);
        for (String segment : segments) {
            if (!ActionPattern.isLiteralSegment(segment)) {
                throw new IllegalArgumentException("Invalid action segment in '" + value + "': " + segment);
            }
        }
        return new Action(value, segments);
    }

    public String value() { return value; }
    public int segmentCount() { return segments.length; }
    public String segmentAt(int index) { return segments[index]; }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Action action && value.equals(action.value);
    }

    @Override
    public int hashCode() { return value.hashCode(); }

    @Override
    public String toString() { return value; }
}
