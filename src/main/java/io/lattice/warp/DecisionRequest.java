package io.lattice.warp;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Snapshot of one authorization question. Use stable IDs and a precomputed role closure. */
public record DecisionRequest(
        SubjectId subject,
        SubjectId actor,
        SubjectId target,
        Action action,
        Set<String> effectiveRoles,
        EvaluationContext context,
        Instant at) {

    public DecisionRequest {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(at, "at");
        TreeSet<String> roles = new TreeSet<>();
        if (effectiveRoles != null) {
            for (String role : effectiveRoles) {
                Objects.requireNonNull(role, "role");
                roles.add(role.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        effectiveRoles = Collections.unmodifiableSet(roles);
    }

    public static DecisionRequest forSubject(SubjectId subject, String action, Set<String> roles,
                                             EvaluationContext context, Instant at) {
        return new DecisionRequest(subject, null, null, Action.of(action), roles, context, at);
    }
}
