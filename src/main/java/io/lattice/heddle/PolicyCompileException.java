package io.lattice.heddle;

import java.util.List;

public final class PolicyCompileException extends IllegalArgumentException {
    private final List<Diagnostic> diagnostics;

    public PolicyCompileException(List<Diagnostic> diagnostics) {
        super(diagnostics.stream().map(d -> d.code() + " at " + d.pointer() + ": " + d.message())
                .reduce((left, right) -> left + System.lineSeparator() + right).orElse("Policy compilation failed"));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostic> diagnostics() { return diagnostics; }
}
