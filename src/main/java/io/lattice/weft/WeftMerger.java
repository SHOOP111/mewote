package io.lattice.weft;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Deterministic event merge. A rejected invariant leaves the caller's local snapshot untouched. */
public final class WeftMerger {
    public static final Duration DECLARED_PROPAGATION_BOUND = Duration.ofSeconds(2);
    public enum Status { APPLIED, NO_CHANGE, QUARANTINED }
    public record MergeResult(Status status, WeftSnapshot snapshot, List<String> newlyAppliedEventIds, String alert) {
        public MergeResult { newlyAppliedEventIds = List.copyOf(newlyAppliedEventIds); }
    }

    public MergeResult merge(WeftSnapshot local, List<Mutation> incoming, Predicate<WeftSnapshot> invariant) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(incoming, "incoming");
        Objects.requireNonNull(invariant, "invariant");
        Map<String, Mutation> events = new TreeMap<>(local.events());
        List<String> added = new ArrayList<>();
        for (Mutation mutation : incoming) {
            Mutation previous = events.putIfAbsent(mutation.eventId(), mutation);
            if (previous == null) added.add(mutation.eventId());
            else if (!previous.equals(mutation)) {
                return new MergeResult(Status.QUARANTINED, local, List.of(),
                        "Event ID collision with different signed content: " + mutation.eventId());
            }
        }
        if (added.isEmpty()) return new MergeResult(Status.NO_CHANGE, local, List.of(), "");

        List<Mutation> ordered = new ArrayList<>(events.values());
        ordered.sort(Comparator.comparing(Mutation::clock).thenComparing(Mutation::eventId));
        Map<String, Mutation> effective = new HashMap<>();
        for (Mutation mutation : ordered) {
            Mutation current = effective.get(mutation.resourceKey());
            if (current != null && current.kind() == Mutation.Kind.REVOKE
                    && mutation.kind() == Mutation.Kind.GRANT && !mutation.approvedRegrant()) {
                continue; // Revocation is monotonic unless a separately authorized regrant is explicit.
            }
            effective.put(mutation.resourceKey(), mutation);
        }
        WeftSnapshot candidate = new WeftSnapshot(effective, events);
        if (!invariant.test(candidate)) {
            return new MergeResult(Status.QUARANTINED, local, List.of(),
                    "Candidate merge violates a configured invariant; quarantined locally and must alert before propagation.");
        }
        return new MergeResult(Status.APPLIED, candidate, added, "");
    }
}
