package io.lattice.heddle;

import java.util.Objects;

public record Diagnostic(Severity severity, String code, String pointer, String message) {
    public enum Severity { ERROR, WARNING, INFO }
    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(message, "message");
    }
}
