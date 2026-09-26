package io.lattice.heddle;

import io.lattice.warp.Authority;
import io.lattice.warp.CompiledPolicy;
import io.lattice.warp.CompiledRule;
import io.lattice.warp.PolicyRule;
import io.lattice.warp.RoleDefinition;
import io.lattice.warp.RoleGraph;
import io.lattice.warp.RuleBand;
import io.lattice.warp.RuleEffect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Heddle's parse/typecheck/lint/compile pipeline. */
public final class PolicyCompiler {
    private static final Comparator<PolicyRule> TOTAL_ORDER = (left, right) -> {
        int order = Integer.compare(left.band().rank(), right.band().rank());
        if (order != 0) return order;
        order = left.action().compareSpecificity(right.action());
        if (order != 0) return order;
        order = Integer.compare(effectRank(left.effect()), effectRank(right.effect()));
        if (order != 0) return order;
        order = Integer.compare(right.authority().rank(), left.authority().rank());
        if (order != 0) return order;
        order = Integer.compare(right.priority(), left.priority());
        if (order != 0) return order;
        order = Integer.compare(right.sourceOrder(), left.sourceOrder());
        if (order != 0) return order;
        return left.id().compareTo(right.id());
    };

    private final PolicyParser parser;

    public PolicyCompiler() { this(new PolicyParser()); }
    public PolicyCompiler(PolicyParser parser) { this.parser = parser; }

    public Compilation compile(String source) {
        PolicySource parsed = parser.parse(source);
        return compile(parsed, source);
    }

    public Compilation compile(PolicySource source, String canonicalSource) {
        if (source.schemaVersion() != 1) {
            throw failure("UNSUPPORTED_SCHEMA", "/schemaVersion", "This compiler accepts schemaVersion 1; upgrade the policy explicitly before compiling it.");
        }
        RoleGraph roleGraph;
        try {
            roleGraph = new RoleGraph(source.roles());
        } catch (IllegalArgumentException invalid) {
            throw failure("INVALID_ROLE_GRAPH", "/roles", invalid.getMessage());
        }

        Map<String, PolicyRule> byId = new HashMap<>();
        for (int i = 0; i < source.rules().size(); i++) {
            PolicyRule rule = source.rules().get(i);
            if (byId.putIfAbsent(rule.id(), rule) != null) {
                throw failure("DUPLICATE_RULE_ID", "/rules/" + i + "/id", "Rule IDs are unique across the complete policy: " + rule.id());
            }
            validateRule(rule, i, roleGraph);
        }

        Map<String, Set<String>> overriddenBy = validateOverrides(source, byId, roleGraph);
        validateInheritedCeilings(source, roleGraph);
        List<PolicyRule> ordered = new ArrayList<>(source.rules());
        ordered.sort(TOTAL_ORDER);
        List<CompiledRule> compiled = new ArrayList<>(ordered.size());
        for (int rank = 0; rank < ordered.size(); rank++) {
            PolicyRule rule = ordered.get(rank);
            compiled.add(new CompiledRule(rule, rank, overriddenBy.getOrDefault(rule.id(), Set.of())));
        }
        CompiledPolicy policy = new CompiledPolicy(sha256(canonicalSource), roleGraph, compiled);
        List<Diagnostic> diagnostics = lint(source.rules());
        return new Compilation(policy, diagnostics);
    }

    private void validateRule(PolicyRule rule, int sourceIndex, RoleGraph roles) {
        String pointer = "/rules/" + sourceIndex;
        if (rule.id().length() > 128 || !rule.id().matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}")) {
            throw failure("INVALID_RULE_ID", pointer + "/id", "Use a stable rule ID containing letters, digits, '.', '_', ':', or '-'.");
        }
        if (rule.band() == RuleBand.INVARIANT) {
            throw failure("RESERVED_BAND", pointer + "/band", "INVARIANT is compiled into Selvedge and cannot be authored in policy.");
        }
        if (rule.authority() == Authority.SELVEDGE) {
            throw failure("RESERVED_AUTHORITY", pointer + "/authority", "SELVEDGE is compiled in and cannot be claimed by a policy.");
        }
        if (rule.band() == RuleBand.BARRIER && rule.effect() != RuleEffect.BARRIER) {
            throw failure("BARRIER_EFFECT_REQUIRED", pointer, "The BARRIER band requires effect BARRIER.");
        }
        if (rule.effect() == RuleEffect.BARRIER && rule.band() != RuleBand.BARRIER) {
            throw failure("BARRIER_BAND_REQUIRED", pointer, "A BARRIER effect is absolute and must use the BARRIER band.");
        }
        if (rule.band() == RuleBand.INHERITED_GRANT && rule.effect() != RuleEffect.ALLOW) {
            throw failure("INHERITED_GRANT_MUST_ALLOW", pointer, "INHERITED_GRANT contains grants only; author denials in EXPLICIT.");
        }
        if (rule.band() == RuleBand.NAMESPACE_DEFAULT && rule.authority() != Authority.NAMESPACE) {
            throw failure("NAMESPACE_AUTHORITY_REQUIRED", pointer + "/authority", "NAMESPACE_DEFAULT rules must use NAMESPACE authority.");
        }
        if (rule.authority() == Authority.NAMESPACE && rule.band() != RuleBand.NAMESPACE_DEFAULT) {
            throw failure("NAMESPACE_BAND_REQUIRED", pointer + "/band", "NAMESPACE authority is reserved for NAMESPACE_DEFAULT rules.");
        }
        if (rule.role() != null) {
            if (rule.authority() != Authority.ROLE) {
                throw failure("ROLE_AUTHORITY_REQUIRED", pointer + "/authority", "A role-bound rule must use ROLE authority.");
            }
            RoleDefinition role = roles.role(rule.role());
            if (role == null) throw failure("UNKNOWN_ROLE", pointer + "/role", "No role named '" + rule.role() + "' is declared.");
            if (rule.effect() == RuleEffect.ALLOW && !role.ceilingContains(rule.action())) {
                throw failure("CEILING_NOT_PROVEN", pointer + "/action", "This grant is not fully contained by a single capability pattern in role '" + rule.role() + "' ceiling. Widen the declared ceiling intentionally or narrow the grant.");
            }
        } else if (rule.authority() == Authority.ROLE) {
            throw failure("ROLE_REQUIRED", pointer + "/role", "ROLE authority must name its owning role so its capability ceiling can be enforced.");
        }
        if (rule.band() == RuleBand.INHERITED_GRANT && rule.role() == null) {
            throw failure("INHERITED_ROLE_REQUIRED", pointer + "/role", "An inherited grant must name its source role.");
        }
        if (rule.budget() != null && rule.effect() != RuleEffect.ALLOW) {
            throw failure("BUDGET_ON_NON_GRANT", pointer + "/budget", "Budgets attach to an allow reservation, not to a denial or barrier.");
        }
    }

    private void validateInheritedCeilings(PolicySource source, RoleGraph roles) {
        for (RoleDefinition descendant : source.roles().values()) {
            Set<String> path = roles.inheritedBy(descendant.id());
            for (PolicyRule grant : source.rules()) {
                if (grant.effect() != RuleEffect.ALLOW || grant.role() == null || !path.contains(grant.role())) continue;
                if (grant.role().equals(descendant.id()) || descendant.overrides().contains(grant.id())) continue;
                if (!descendant.ceilingContains(grant.action())) {
                    throw failure("INHERITED_CEILING_ESCAPE", "/roles/" + descendant.id() + "/ceiling",
                            "Inherited grant '" + grant.id() + "' from role '" + grant.role()
                                    + "' is outside this role's capability set. Narrow the inherited grant, widen this ceiling intentionally, or declare a valid explicit override.");
                }
            }
        }
    }

    private Map<String, Set<String>> validateOverrides(PolicySource source, Map<String, PolicyRule> byId,
                                                        RoleGraph roles) {
        Map<String, Set<String>> overriddenBy = new HashMap<>();
        for (RoleDefinition role : source.roles().values()) {
            for (String targetId : role.overrides()) {
                PolicyRule target = byId.get(targetId);
                if (target == null) throw failure("UNKNOWN_OVERRIDE_TARGET", "/roles/" + role.id() + "/overrides", "No rule named '" + targetId + "' exists.");
                if (target.role() == null || target.effect() == RuleEffect.BARRIER
                        || !roles.inheritedBy(role.id()).contains(target.role()) || target.role().equals(role.id())) {
                    throw failure("NOT_INHERITED_OVERRIDE", "/roles/" + role.id() + "/overrides", "Rule '" + targetId + "' is not a soft rule inherited by role '" + role.id() + "'. Barriers cannot be overridden.");
                }
                boolean replacementExists = source.rules().stream().anyMatch(rule -> role.id().equals(rule.role())
                        && rule.action().equals(target.action()) && rule.band() == RuleBand.EXPLICIT);
                if (!replacementExists) throw failure("OVERRIDE_WITHOUT_REPLACEMENT", "/roles/" + role.id() + "/overrides", "Declare an EXPLICIT rule owned by '" + role.id() + "' for the same action pattern as '" + targetId + "'.");
                overriddenBy.computeIfAbsent(targetId, ignored -> new TreeSet<>()).add(role.id());
            }
        }
        Map<String, Set<String>> immutable = new HashMap<>();
        overriddenBy.forEach((id, rolesSet) -> immutable.put(id, Set.copyOf(rolesSet)));
        return immutable;
    }

    private List<Diagnostic> lint(List<PolicyRule> rules) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            PolicyRule rule = rules.get(i);
            if (rule.action().broad()) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, "BROAD_WILDCARD", "/rules/" + i + "/action",
                        "Pattern '" + rule.action() + "' has a wide match surface. Review its blast radius before applying it."));
            }
            for (int j = i + 1; j < rules.size(); j++) {
                PolicyRule other = rules.get(j);
                if (sameScope(rule, other) && rule.action().equals(other.action()) && rule.band() == other.band()) {
                    PolicyRule winner = TOTAL_ORDER.compare(rule, other) <= 0 ? rule : other;
                    PolicyRule shadowed = winner == rule ? other : rule;
                    diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, "SHADOWED_RULE", "/rules/" + sourceIndex(rules, shadowed),
                            "Rule '" + shadowed.id() + "' cannot win against '" + winner.id() + "' when their identical scope matches."));
                }
                if (rule.action().equals(other.action()) && rule.effect() != other.effect()
                        && rule.guard().expectedValues().containsKey("region")
                        && other.guard().expectedValues().containsKey("region")
                        && !rule.guard().expectedValues().get("region").equals(other.guard().expectedValues().get("region"))) {
                    diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, "REGION_UNION_REVIEW", "/rules/" + j,
                            "Opposing rules for the same action name different regions. Confirm their overlap and intended composition against the spatial index."));
                }
            }
        }
        return List.copyOf(diagnostics);
    }

    private boolean sameScope(PolicyRule left, PolicyRule right) {
        return left.authority() == right.authority() && java.util.Objects.equals(left.role(), right.role())
                && java.util.Objects.equals(left.subject(), right.subject())
                && left.guard().expectedValues().equals(right.guard().expectedValues())
                && java.util.Objects.equals(left.guard().notBefore(), right.guard().notBefore())
                && java.util.Objects.equals(left.guard().expiresAt(), right.guard().expiresAt())
                && java.util.Objects.equals(left.guard().schedule(), right.guard().schedule());
    }

    private int sourceIndex(List<PolicyRule> rules, PolicyRule sought) { return rules.indexOf(sought); }

    private static int effectRank(RuleEffect effect) {
        return switch (effect) {
            case DENY, BARRIER -> 0;
            case ALLOW -> 1;
        };
    }

    private static String sha256(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static PolicyCompileException failure(String code, String pointer, String message) {
        return new PolicyCompileException(List.of(new Diagnostic(Diagnostic.Severity.ERROR, code, pointer, message)));
    }
}
