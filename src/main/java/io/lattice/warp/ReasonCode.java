package io.lattice.warp;

public enum ReasonCode {
    RULE_ALLOW,
    RULE_DENY,
    BARRIER,
    INVARIANT_SELF_ESCALATION,
    INVARIANT_CEILING_MUTATION,
    DEFAULT_DENY,
    BUDGET_EXHAUSTED,
    ABSTAIN
}
