package io.lattice.warp;

/** Lower ordinal is considered earlier. Explicit allow and soft-deny share one arbitration band. */
public enum RuleBand {
    INVARIANT(0),
    BARRIER(1),
    EXPLICIT(2),
    INHERITED_GRANT(3),
    NAMESPACE_DEFAULT(4);

    private final int rank;
    RuleBand(int rank) { this.rank = rank; }
    public int rank() { return rank; }
}
