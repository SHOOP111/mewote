package io.lattice.beacon;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Ordered self-diagnosis checks. A failure is returned as data rather than hidden by fallback. */
public final class Doctor {
    public enum Status { PASS, WARN, FAIL }
    public record Check(String id, Status status, String evidence) { }
    public record Report(Instant checkedAt, List<Check> checks) {
        public Report { checks = List.copyOf(checks); }
        public boolean healthy() { return checks.stream().noneMatch(check -> check.status() == Status.FAIL); }
    }
    public record CheckResult(Status status, String evidence) { }
    private record RegisteredCheck(String id, Supplier<CheckResult> action) { }
    private final List<RegisteredCheck> checks = new ArrayList<>();

    public synchronized void register(String id, Supplier<CheckResult> action) {
        Objects.requireNonNull(id, "id");
        if (checks.stream().anyMatch(check -> check.id.equals(id))) throw new IllegalArgumentException("Duplicate doctor check: " + id);
        checks.add(new RegisteredCheck(id, action));
    }

    public synchronized Report run(Instant at) {
        List<Check> results = new ArrayList<>();
        for (RegisteredCheck check : checks) {
            try {
                CheckResult result = check.action.get();
                results.add(new Check(check.id, result.status(), result.evidence()));
            } catch (RuntimeException failure) {
                results.add(new Check(check.id, Status.FAIL, failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            }
        }
        return new Report(at, results);
    }
}
