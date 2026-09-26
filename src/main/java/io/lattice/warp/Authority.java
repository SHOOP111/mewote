package io.lattice.warp;

/** Independent authorities. Higher value only breaks equal-specificity, equal-effect ties. */
public enum Authority {
    NETWORK(500),
    SPATIAL(400),
    ROLE(300),
    PLUGIN(200),
    NAMESPACE(100),
    SELVEDGE(1000);

    private final int rank;
    Authority(int rank) { this.rank = rank; }
    public int rank() { return rank; }
}
