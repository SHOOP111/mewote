package io.lattice.thread;

import java.util.List;

/** Structured, colour-independent rendering with an accessible severity label and copyable text. */
public record Explanation(String severityLabel, List<Segment> segments, String plainText) {
    public Explanation { segments = List.copyOf(segments); }
    public record Segment(String translationKey, String text) { }
}
