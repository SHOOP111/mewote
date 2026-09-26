package io.lattice.skein;

import io.lattice.thread.ReasoningRecord;
import io.lattice.warp.Verdict;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Filtered forensic journal. It is explicitly not the authoritative state source. */
public final class DecisionJournal {
    public enum Cause { SENSITIVE_DENIAL, ANOMALY, STATE_CHANGE, UNIFORM_SAMPLE, ON_DEMAND }
    public record Entry(ReasoningRecord record, Instant capturedAt, Cause cause) { }
    private final List<Entry> entries = new ArrayList<>();
    private volatile CaptureWindow captureWindow;

    public synchronized boolean record(ReasoningRecord record, Instant capturedAt, boolean anomaly,
                                       boolean stateChange, double sampleRate) {
        if (sampleRate < 0 || sampleRate > 1) throw new IllegalArgumentException("sampleRate must be in [0,1]");
        Cause cause = null;
        if (record.verdict() == Verdict.DENY && (record.action().startsWith("lattice.") || record.action().contains("admin"))) cause = Cause.SENSITIVE_DENIAL;
        else if (anomaly) cause = Cause.ANOMALY;
        else if (stateChange) cause = Cause.STATE_CHANGE;
        else if (captureWindow != null && captureWindow.includes(record.subjectId(), capturedAt)) cause = Cause.ON_DEMAND;
        else if (ThreadLocalRandom.current().nextDouble() < sampleRate) cause = Cause.UNIFORM_SAMPLE;
        if (cause == null) return false;
        entries.add(new Entry(record, capturedAt, cause));
        return true;
    }

    public synchronized void capture(String subjectId, Instant from, Instant until) {
        if (!from.isBefore(until)) throw new IllegalArgumentException("Capture window must be non-empty");
        captureWindow = new CaptureWindow(subjectId, from, until);
    }

    public synchronized List<Entry> entries() { return List.copyOf(entries); }
    public synchronized List<Entry> forSubject(String subjectId, Instant from, Instant until) {
        return entries.stream().filter(e -> e.record().subjectId().equals(subjectId)
                && !e.capturedAt().isBefore(from) && e.capturedAt().isBefore(until)).toList();
    }

    private record CaptureWindow(String subjectId, Instant from, Instant until) {
        private boolean includes(String subject, Instant at) {
            return (subjectId == null || subjectId.equals(subject)) && !at.isBefore(from) && at.isBefore(until);
        }
    }
}
