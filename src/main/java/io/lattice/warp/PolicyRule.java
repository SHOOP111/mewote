package io.lattice.warp;

import io.lattice.quanta.BudgetSpec;

import java.util.Objects;

/** Fully typed rule after Heddle parsing. Source position is captured before compilation. */
public record PolicyRule(
        String id,
        ActionPattern action,
        RuleEffect effect,
        RuleBand band,
        Authority authority,
        String role,
        SubjectId subject,
        int priority,
        int sourceOrder,
        RuleGuard guard,
        BudgetSpec budget) {

    public PolicyRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(band, "band");
        Objects.requireNonNull(authority, "authority");
        if (id.isBlank()) throw new IllegalArgumentException("Rule ID cannot be blank");
        if (role != null) role = role.trim().toLowerCase(java.util.Locale.ROOT);
        if (guard == null) guard = RuleGuard.any();
    }
}
