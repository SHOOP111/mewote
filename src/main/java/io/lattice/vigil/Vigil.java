package io.lattice.vigil;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Explainable baseline checks and heuristics; no opaque model or untraceable score. */
public final class Vigil {
    public enum Operation { GRANT, REVOKE }
    public enum Severity { REVIEW, HIGH, CRITICAL }
    public enum Code { BASELINE_VIOLATION, GRANT_BURST, OFF_HOURS_CHANGE, DORMANT_ACCOUNT_REVIVAL, SCOPE_CREEP, PERMISSION_CHURN }
    public record GrantEvent(String eventId, String actorId, String subjectId, String scope,
                             int scopeDepth, Operation operation, Instant at,
                             boolean baselineAuthorized, boolean dormantSubject) { }
    public record Alert(long id, Code code, Severity severity, String actorId, String subjectId,
                        Instant detectedAt, List<String> evidence, String dismissalReason) {
        public Alert { evidence = List.copyOf(evidence); }
    }
    public record Config(int burstThreshold, Duration burstWindow, int churnThreshold,
                         int maximumScopeDepth, ZoneId zone, LocalTime permittedStart, LocalTime permittedEnd) {
        public Config {
            if (burstThreshold < 1 || churnThreshold < 1 || maximumScopeDepth < 0) throw new IllegalArgumentException("Invalid Vigil threshold");
            if (burstWindow == null || burstWindow.isNegative() || burstWindow.isZero()) throw new IllegalArgumentException("burstWindow must be positive");
            if (zone == null || permittedStart == null || permittedEnd == null || permittedStart.equals(permittedEnd)) throw new IllegalArgumentException("Explicit non-empty baseline time window is required");
        }
    }
    public record Inspection(List<Alert> alerts, boolean frozen) { public Inspection { alerts = List.copyOf(alerts); } }

    private final Config config;
    private final AtomicLong ids = new AtomicLong();
    private final Deque<GrantEvent> recent = new ArrayDeque<>();
    private final Map<Long, Alert> alerts = new HashMap<>();
    private final Set<String> frozenActors = new HashSet<>();
    private final Map<String, List<String>> freezeHistory = new HashMap<>();
    private final List<Consumer<Alert>> listeners = new CopyOnWriteArrayList<>();

    public Vigil(Config config) { this.config = config; }

    public synchronized Inspection inspect(GrantEvent event) {
        List<Alert> emitted = new ArrayList<>();
        Instant floor = event.at().minus(config.burstWindow());
        while (!recent.isEmpty() && recent.peekFirst().at().isBefore(floor)) recent.removeFirst();
        recent.addLast(event);
        List<GrantEvent> sameActor = recent.stream().filter(e -> e.actorId().equals(event.actorId())).toList();
        List<GrantEvent> sameSubject = recent.stream().filter(e -> e.subjectId().equals(event.subjectId())).toList();

        if (!event.baselineAuthorized()) emitted.add(alert(Code.BASELINE_VIOLATION, Severity.CRITICAL, event,
                List.of("The compiled compliance baseline did not authorize this mutation.", "Policy grant paused pending review.")));
        if (sameActor.size() >= config.burstThreshold()) emitted.add(alert(Code.GRANT_BURST, Severity.HIGH, event,
                List.of("actor mutations in window=" + sameActor.size(), "window=" + config.burstWindow())));
        if (!insidePermittedHours(event.at())) emitted.add(alert(Code.OFF_HOURS_CHANGE, Severity.REVIEW, event,
                List.of("zone=" + config.zone(), "localTime=" + event.at().atZone(config.zone()).toLocalTime())));
        if (event.dormantSubject()) emitted.add(alert(Code.DORMANT_ACCOUNT_REVIVAL, Severity.HIGH, event,
                List.of("The target was marked dormant before this authority change.")));
        if (event.scopeDepth() > config.maximumScopeDepth()) emitted.add(alert(Code.SCOPE_CREEP, Severity.HIGH, event,
                List.of("scopeDepth=" + event.scopeDepth(), "baselineMaximum=" + config.maximumScopeDepth())));
        if (sameSubject.size() >= config.churnThreshold()) emitted.add(alert(Code.PERMISSION_CHURN, Severity.HIGH, event,
                List.of("subject mutations in window=" + sameSubject.size(), "window=" + config.burstWindow())));

        return new Inspection(emitted, frozenActors.contains(event.actorId()));
    }

    private Alert alert(Code code, Severity severity, GrantEvent event, List<String> evidence) {
        Alert alert = new Alert(ids.incrementAndGet(), code, severity, event.actorId(), event.subjectId(), event.at(), evidence, null);
        alerts.put(alert.id(), alert);
        if (severity == Severity.HIGH || severity == Severity.CRITICAL) {
            frozenActors.add(event.actorId());
            freezeHistory.computeIfAbsent(event.actorId(), ignored -> new ArrayList<>())
                    .add("frozen by alert " + alert.id() + " (" + code + ") at " + event.at());
        }
        listeners.forEach(listener -> listener.accept(alert));
        return alert;
    }

    public synchronized Alert dismiss(long alertId, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A dismissal reason is mandatory");
        Alert old = alerts.get(alertId);
        if (old == null) throw new IllegalArgumentException("Unknown alert: " + alertId);
        Alert updated = new Alert(old.id(), old.code(), old.severity(), old.actorId(), old.subjectId(), old.detectedAt(), old.evidence(), reason.trim());
        alerts.put(alertId, updated);
        return updated;
    }

    public synchronized boolean unfreeze(String actorId, String recordedReason) {
        if (recordedReason == null || recordedReason.isBlank()) throw new IllegalArgumentException("Unfreeze requires an audited reason");
        boolean removed = frozenActors.remove(actorId);
        if (removed) freezeHistory.computeIfAbsent(actorId, ignored -> new ArrayList<>())
                .add("unfrozen: " + recordedReason.trim());
        return removed;
    }

    public synchronized List<String> freezeHistory(String actorId) {
        return List.copyOf(freezeHistory.getOrDefault(actorId, List.of()));
    }

    public synchronized boolean isFrozen(String actorId) { return frozenActors.contains(actorId); }
    public synchronized List<Alert> alerts() { return alerts.values().stream().sorted(java.util.Comparator.comparingLong(Alert::id)).toList(); }
    public void onAlert(Consumer<Alert> listener) { listeners.add(listener); }

    private boolean insidePermittedHours(Instant instant) {
        LocalTime time = instant.atZone(config.zone()).toLocalTime();
        if (config.permittedStart().isBefore(config.permittedEnd())) {
            return !time.isBefore(config.permittedStart()) && time.isBefore(config.permittedEnd());
        }
        return !time.isBefore(config.permittedStart()) || time.isBefore(config.permittedEnd());
    }
}
