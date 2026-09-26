package io.lattice.tessellation;

import io.lattice.warp.RuleEffect;

public record WorldOverride(String worldId, String actionPattern, RuleEffect effect, String sourceId) { }
