package io.lattice.ply;

import java.time.Instant;

/** Display-name history for audit only; never used as an authorization key. */
public record NameChange(String name, Instant observedAt) { }
