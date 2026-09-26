package io.lattice.thread;

import io.lattice.warp.ReasonCode;
import io.lattice.warp.RuleBand;
import io.lattice.warp.Verdict;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Deterministic evidence record. It intentionally contains no implicit timestamp or random request ID. */
public final class ReasoningRecord {
    private final String policyHash;
    private final String subjectId;
    private final String action;
    private final Verdict verdict;
    private final ReasonCode reason;
    private final RuleBand band;
    private final String winningRuleId;
    private final boolean barrier;
    private final List<TraceStep> trace;
    private final String digest;

    public ReasoningRecord(String policyHash, String subjectId, String action, Verdict verdict,
                           ReasonCode reason, RuleBand band, String winningRuleId, boolean barrier,
                           List<TraceStep> trace) {
        this.policyHash = Objects.requireNonNull(policyHash, "policyHash");
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
        this.action = Objects.requireNonNull(action, "action");
        this.verdict = Objects.requireNonNull(verdict, "verdict");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.band = band;
        this.winningRuleId = winningRuleId;
        this.barrier = barrier;
        this.trace = List.copyOf(trace);
        this.digest = sha256(canonicalWithoutDigest());
    }

    public String canonicalWithoutDigest() {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"policyHash\":").append(quote(policyHash))
                .append(",\"subjectId\":").append(quote(subjectId))
                .append(",\"action\":").append(quote(action))
                .append(",\"verdict\":").append(quote(verdict.name()))
                .append(",\"reason\":").append(quote(reason.name()))
                .append(",\"band\":").append(band == null ? "null" : quote(band.name()))
                .append(",\"winningRuleId\":").append(winningRuleId == null ? "null" : quote(winningRuleId))
                .append(",\"barrier\":").append(barrier)
                .append(",\"trace\":[");
        for (int i = 0; i < trace.size(); i++) {
            if (i > 0) out.append(',');
            TraceStep step = trace.get(i);
            out.append("{\"ruleId\":").append(quote(step.ruleId()))
                    .append(",\"band\":").append(step.band() == null ? "null" : quote(step.band().name()))
                    .append(",\"authority\":").append(step.authority() == null ? "null" : quote(step.authority().name()))
                    .append(",\"effect\":").append(step.effect() == null ? "null" : quote(step.effect().name()))
                    .append(",\"outcome\":").append(quote(step.outcome().name()))
                    .append(",\"detail\":").append(quote(step.detail())).append('}');
        }
        return out.append("]}").toString();
    }

    public String canonical() {
        return canonicalWithoutDigest().substring(0, canonicalWithoutDigest().length() - 1)
                + ",\"digest\":" + quote(digest) + "}";
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.append('"').toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    public String policyHash() { return policyHash; }
    public String subjectId() { return subjectId; }
    public String action() { return action; }
    public Verdict verdict() { return verdict; }
    public ReasonCode reason() { return reason; }
    public RuleBand band() { return band; }
    public String winningRuleId() { return winningRuleId; }
    public boolean barrier() { return barrier; }
    public List<TraceStep> trace() { return trace; }
    public String digest() { return digest; }
}
