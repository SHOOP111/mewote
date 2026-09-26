package io.lattice.warp;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** A role's capability ceiling is a set of action patterns, not a rank or display label. */
public record RoleDefinition(String id, Set<String> inherits, List<ActionPattern> ceilings, Set<String> overrides) {
    public RoleDefinition {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[a-z0-9][a-z0-9._-]{0,127}")) throw new IllegalArgumentException("Invalid role ID: " + id);
        TreeSet<String> parents = new TreeSet<>();
        if (inherits != null) inherits.forEach(parent -> parents.add(parent.toLowerCase(java.util.Locale.ROOT)));
        inherits = Collections.unmodifiableSet(parents);
        ceilings = ceilings == null ? List.of() : List.copyOf(ceilings);
        TreeSet<String> overrideIds = new TreeSet<>();
        if (overrides != null) overrideIds.addAll(overrides);
        overrides = Collections.unmodifiableSet(overrideIds);
    }

    public boolean ceilingContains(ActionPattern pattern) {
        return ceilings.stream().anyMatch(pattern::isSubsetOf);
    }
}
