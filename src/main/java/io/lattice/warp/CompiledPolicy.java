package io.lattice.warp;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable output of Heddle compilation. */
public final class CompiledPolicy {
    private final String policyHash;
    private final RoleGraph roles;
    private final Map<Authority, RuleIndex> indexes;
    private final List<PolicyRule> rules;

    public CompiledPolicy(String policyHash, RoleGraph roles, List<CompiledRule> compiledRules) {
        this.policyHash = Objects.requireNonNull(policyHash, "policyHash");
        this.roles = Objects.requireNonNull(roles, "roles");
        EnumMap<Authority, RuleIndex> built = new EnumMap<>(Authority.class);
        for (Authority authority : Authority.values()) {
            if (authority == Authority.SELVEDGE) continue;
            built.put(authority, new RuleIndex(authority, compiledRules.stream()
                    .filter(rule -> rule.rule().authority() == authority).toList()));
        }
        this.indexes = Collections.unmodifiableMap(built);
        this.rules = compiledRules.stream().map(CompiledRule::rule).toList();
    }

    public String policyHash() { return policyHash; }
    public RoleGraph roles() { return roles; }
    public RuleIndex index(Authority authority) { return indexes.get(authority); }
    public Map<Authority, RuleIndex> indexes() { return indexes; }
    public List<PolicyRule> rules() { return rules; }
}
