package io.lattice.bridge;

import io.lattice.warp.Action;
import io.lattice.warp.Decision;
import io.lattice.warp.DecisionRequest;
import io.lattice.warp.SubjectId;

import java.util.List;

/** Narrow compatibility contract; server-specific adapters supply immutable context snapshots. */
public interface PermissionProvider {
    Decision check(DecisionRequest request);
    List<Decision> checkBulk(List<DecisionRequest> requests);
    boolean hasPermission(SubjectId subject, Action action, DecisionRequest requestTemplate);
}
