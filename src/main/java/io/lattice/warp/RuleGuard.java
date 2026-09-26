package io.lattice.warp;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Conjunctive context and lifecycle guard. Intervals are half-open: [notBefore, expiresAt). */
public final class RuleGuard {
    public enum Failure { NONE, NOT_YET_VALID, EXPIRED, CONTEXT_MISMATCH, OUTSIDE_SCHEDULE }
    public record Evaluation(boolean matches, Failure failure, String key) {
        public static Evaluation match() { return new Evaluation(true, Failure.NONE, ""); }
    }

    private final Map<String, Set<String>> expectedValues;
    private final Instant notBefore;
    private final Instant expiresAt;
    private final WeeklySchedule schedule;

    public RuleGuard(Map<String, Set<String>> expectedValues, Instant notBefore, Instant expiresAt,
                     WeeklySchedule schedule) {
        TreeMap<String, Set<String>> ordered = new TreeMap<>();
        if (expectedValues != null) {
            expectedValues.forEach((key, values) -> {
                Objects.requireNonNull(key, "guard key");
                Objects.requireNonNull(values, "guard values");
                TreeSet<String> set = new TreeSet<>(values);
                if (set.isEmpty()) throw new IllegalArgumentException("Guard value set cannot be empty: " + key);
                ordered.put(key, Collections.unmodifiableSet(set));
            });
        }
        if (notBefore != null && expiresAt != null && !notBefore.isBefore(expiresAt)) {
            throw new IllegalArgumentException("notBefore must be earlier than expiresAt");
        }
        this.expectedValues = Collections.unmodifiableMap(ordered);
        this.notBefore = notBefore;
        this.expiresAt = expiresAt;
        this.schedule = schedule;
    }

    public static RuleGuard any() { return new RuleGuard(Map.of(), null, null, null); }

    public Evaluation evaluate(EvaluationContext context, Instant instant) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(instant, "instant");
        if (notBefore != null && instant.isBefore(notBefore)) {
            return new Evaluation(false, Failure.NOT_YET_VALID, "notBefore");
        }
        if (expiresAt != null && !instant.isBefore(expiresAt)) {
            return new Evaluation(false, Failure.EXPIRED, "expiresAt");
        }
        for (Map.Entry<String, Set<String>> entry : expectedValues.entrySet()) {
            String actual = context.value(entry.getKey());
            if (actual == null || !entry.getValue().contains(actual)) {
                return new Evaluation(false, Failure.CONTEXT_MISMATCH, entry.getKey());
            }
        }
        if (schedule != null && !schedule.includes(instant)) {
            return new Evaluation(false, Failure.OUTSIDE_SCHEDULE, "schedule");
        }
        return Evaluation.match();
    }

    public Map<String, Set<String>> expectedValues() { return expectedValues; }
    public Instant notBefore() { return notBefore; }
    public Instant expiresAt() { return expiresAt; }
    public WeeklySchedule schedule() { return schedule; }
}
