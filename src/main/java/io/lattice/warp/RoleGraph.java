package io.lattice.warp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Validated, cycle-free role DAG. Expand assignments when identity state changes, not on each check. */
public final class RoleGraph {
    private final Map<String, RoleDefinition> roles;
    private final Map<String, Set<String>> closures;

    public RoleGraph(Map<String, RoleDefinition> definitions) {
        TreeMap<String, RoleDefinition> sorted = new TreeMap<>(definitions);
        for (RoleDefinition role : sorted.values()) {
            for (String parent : role.inherits()) {
                if (!sorted.containsKey(parent)) throw new IllegalArgumentException("Unknown parent role '" + parent + "' inherited by '" + role.id() + "'");
            }
            if (role.ceilings().isEmpty()) throw new IllegalArgumentException("Role '" + role.id() + "' must declare at least one capability ceiling");
        }
        this.roles = Collections.unmodifiableMap(sorted);
        Map<String, Set<String>> built = new HashMap<>();
        Set<String> visiting = new HashSet<>();
        Set<String> complete = new HashSet<>();
        for (String role : sorted.keySet()) visit(role, visiting, complete, built);
        this.closures = Collections.unmodifiableMap(built);
    }

    private Set<String> visit(String id, Set<String> visiting, Set<String> complete,
                              Map<String, Set<String>> built) {
        if (complete.contains(id)) return built.get(id);
        if (!visiting.add(id)) throw new IllegalArgumentException("Role inheritance cycle includes '" + id + "'");
        TreeSet<String> closure = new TreeSet<>();
        closure.add(id);
        for (String parent : roles.get(id).inherits()) closure.addAll(visit(parent, visiting, complete, built));
        visiting.remove(id);
        complete.add(id);
        Set<String> immutable = Collections.unmodifiableSet(closure);
        built.put(id, immutable);
        return immutable;
    }

    public Set<String> effectiveRoles(Set<String> assigned) {
        TreeSet<String> result = new TreeSet<>();
        for (String role : assigned) {
            Set<String> closure = closures.get(role);
            if (closure == null) throw new IllegalArgumentException("Unknown assigned role: " + role);
            result.addAll(closure);
        }
        return Collections.unmodifiableSet(result);
    }

    public boolean sourceCeilingAllows(String roleId, ActionPattern action) {
        RoleDefinition role = roles.get(roleId);
        return role != null && role.ceilingContains(action);
    }

    public RoleDefinition role(String id) { return roles.get(id); }
    public Map<String, RoleDefinition> roles() { return roles; }
    public Set<String> inheritedBy(String id) { return closures.getOrDefault(id, Set.of()); }
}
