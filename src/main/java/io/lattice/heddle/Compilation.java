package io.lattice.heddle;

import io.lattice.warp.CompiledPolicy;

import java.util.List;

public record Compilation(CompiledPolicy policy, List<Diagnostic> diagnostics) {
    public Compilation { diagnostics = List.copyOf(diagnostics); }
    public long warningCount() { return diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.WARNING).count(); }
}
