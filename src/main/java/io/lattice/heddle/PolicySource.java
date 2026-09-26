package io.lattice.heddle;

import io.lattice.warp.PolicyRule;
import io.lattice.warp.RoleDefinition;

import java.util.List;
import java.util.Map;

public record PolicySource(int schemaVersion, String id, String version, String description,
                           Map<String, String> labels, Map<String, RoleDefinition> roles,
                           List<PolicyRule> rules) {
    public PolicySource {
        labels = Map.copyOf(labels);
        roles = Map.copyOf(roles);
        rules = List.copyOf(rules);
    }
}
