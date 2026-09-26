package io.lattice.ply;

import io.lattice.warp.SubjectId;

import java.util.List;

public record PlyIdentity(SubjectId subjectId, IdentityRisk risk, List<NameChange> nameHistory) {
    public PlyIdentity { nameHistory = List.copyOf(nameHistory); }
    public String currentDisplayName() { return nameHistory.isEmpty() ? "" : nameHistory.get(nameHistory.size() - 1).name(); }
}
