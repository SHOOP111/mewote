package io.lattice.warp;

/** Rule plus its compile-time total-order rank. Smaller ranks win. */
public record CompiledRule(PolicyRule rule, int rank, java.util.Set<String> overriddenByRoles) {
    public CompiledRule {
        if (rank < 0) throw new IllegalArgumentException("rank must be non-negative");
        overriddenByRoles = java.util.Set.copyOf(overriddenByRoles);
    }

    public CompiledRule(PolicyRule rule, int rank) {
        this(rule, rank, java.util.Set.of());
    }

    public boolean isOverriddenFor(DecisionRequest request) {
        return overriddenByRoles.stream().anyMatch(request.effectiveRoles()::contains);
    }
}
