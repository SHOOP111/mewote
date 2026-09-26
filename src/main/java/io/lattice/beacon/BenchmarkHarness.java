package io.lattice.beacon;

import io.lattice.heddle.PolicyCompiler;
import io.lattice.skein.DecisionJournal;
import io.lattice.tessellation.Point2;
import io.lattice.tessellation.Region;
import io.lattice.tessellation.RegionIndex;
import io.lattice.warp.Action;
import io.lattice.warp.ActionPattern;
import io.lattice.warp.Decision;
import io.lattice.warp.DecisionEngine;
import io.lattice.warp.DecisionRequest;
import io.lattice.warp.EvaluationContext;
import io.lattice.warp.SubjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Repeatable smoke benchmark. It reports measurements and environment; it does not enforce targets. */
public final class BenchmarkHarness {
    public record Measurement(String path, int operationsPerSample, int samples, double minNanosPerOperation,
                              double medianNanosPerOperation, double p95NanosPerOperation,
                              double maxNanosPerOperation, double medianOperationsPerSecond) { }
    public record Report(Instant generatedAt, String javaVersion, String vm, String operatingSystem,
                         int availableProcessors, String methodology, List<Measurement> measurements) {
        public Report { measurements = List.copyOf(measurements); }

        public String toJson() {
            StringBuilder json = new StringBuilder("{\n")
                    .append("  \"generatedAt\": ").append(quote(generatedAt.toString())).append(",\n")
                    .append("  \"javaVersion\": ").append(quote(javaVersion)).append(",\n")
                    .append("  \"vm\": ").append(quote(vm)).append(",\n")
                    .append("  \"operatingSystem\": ").append(quote(operatingSystem)).append(",\n")
                    .append("  \"availableProcessors\": ").append(availableProcessors).append(",\n")
                    .append("  \"methodology\": ").append(quote(methodology)).append(",\n")
                    .append("  \"measurements\": [\n");
            for (int i = 0; i < measurements.size(); i++) {
                Measurement value = measurements.get(i);
                if (i > 0) json.append(",\n");
                json.append("    {\"path\": ").append(quote(value.path()))
                        .append(", \"operationsPerSample\": ").append(value.operationsPerSample())
                        .append(", \"samples\": ").append(value.samples())
                        .append(", \"minNanosPerOperation\": ").append(number(value.minNanosPerOperation()))
                        .append(", \"medianNanosPerOperation\": ").append(number(value.medianNanosPerOperation()))
                        .append(", \"p95NanosPerOperation\": ").append(number(value.p95NanosPerOperation()))
                        .append(", \"maxNanosPerOperation\": ").append(number(value.maxNanosPerOperation()))
                        .append(", \"medianOperationsPerSecond\": ").append(number(value.medianOperationsPerSecond())).append('}');
            }
            return json.append("\n  ]\n}\n").toString();
        }

        private static String number(double value) { return String.format(Locale.ROOT, "%.3f", value); }
        private static String quote(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
    }

    @FunctionalInterface private interface Operation { long run(); }
    private static volatile long blackhole;
    private final int operationsPerSample;
    private final int samples;

    public BenchmarkHarness() { this(10_000, 12); }
    public BenchmarkHarness(int operationsPerSample, int samples) {
        if (operationsPerSample < 1 || samples < 5) throw new IllegalArgumentException("Use at least one operation and five measurement samples");
        this.operationsPerSample = operationsPerSample;
        this.samples = samples;
    }

    public Report run() {
        String policySource = "{\"schemaVersion\":1,\"meta\":{\"id\":\"benchmark.smoke\",\"version\":\"1\",\"description\":\"CI smoke benchmark only\"},"
                + "\"roles\":{},\"rules\":[{\"id\":\"benchmark.context.allow\",\"action\":\"demo.block.place\",\"effect\":\"ALLOW\",\"band\":\"EXPLICIT\",\"authority\":\"NETWORK\","
                + "\"guard\":{\"world\":[\"bench\"],\"region\":[\"spawn\"]},"
                + "\"budget\":{\"capacity\":2147483647,\"refillTokens\":1,\"refillPeriodSeconds\":86400}}]}";
        var compiled = new PolicyCompiler().compile(policySource).policy();
        DecisionEngine engine = new DecisionEngine(compiled);
        SubjectId subject = SubjectId.java(UUID.fromString("55555555-5555-4555-8555-555555555555"));
        Action action = Action.of("demo.block.place");
        ActionPattern pattern = ActionPattern.parse("demo.block.place");
        EvaluationContext context = new EvaluationContext("bench", "spawn", "healthy", "creative", "benchmark", Map.of());
        DecisionRequest request = new DecisionRequest(subject, null, null, action, Set.of(), context, Instant.parse("2026-01-01T00:00:00Z"));
        Decision warmDecision = engine.evaluate(request);
        if (!warmDecision.allowed() || warmDecision.lease() == null) throw new IllegalStateException("Benchmark fixture did not produce a budgeted allow");
        warmDecision.lease().release();

        Region region = new Region("spawn", "bench", List.of(new Point2(0, 0), new Point2(100, 0),
                new Point2(100, 100), new Point2(0, 100)), Set.of());
        RegionIndex spatial = new RegionIndex("benchmark-snapshot", List.of(region), List.of());
        DecisionJournal journal = new DecisionJournal();
        Instant capturedAt = request.at();
        Decision auditDecision = engine.evaluate(request);
        if (auditDecision.lease() != null) auditDecision.lease().release();

        List<Measurement> results = new ArrayList<>();
        results.add(measure("fast_pattern_match", () -> pattern.matches(action) ? 1 : 0));
        results.add(measure("context_guard_and_atomic_budget", () -> {
            boolean inRegion = !spatial.at("bench", 32, 32).isEmpty();
            Decision decision = engine.evaluate(request);
            if (decision.lease() != null) decision.lease().commit();
            return (inRegion && decision.allowed()) ? 1 : 0;
        }));
        results.add(measure("spatial_index_lookup", () -> spatial.at("bench", 32, 32).size()));
        results.add(measure("audit_record_render_and_journal", () -> {
            int length = auditDecision.record().canonical().length();
            boolean captured = journal.record(auditDecision.record(), capturedAt, true, false, 0);
            return length + (captured ? 1 : 0);
        }));

        String methodology = "Warm-up: 5 batches of 2,000 operations. Measurement: " + samples + " batches x "
                + operationsPerSample + " operations; per-operation values are batch elapsed/operation and are noisy on shared CI. "
                + "Fast path is precompiled pattern matching; context path includes an end-to-end decision, guard, region lookup and atomic budget lease; "
                + "audit path serializes a record and appends to the decision journal. Smoke data only; no target is enforced or claimed achieved.";
        return new Report(Instant.now(), System.getProperty("java.version"), System.getProperty("java.vm.name"),
                System.getProperty("os.name") + " " + System.getProperty("os.version"),
                Runtime.getRuntime().availableProcessors(), methodology, results);
    }

    private Measurement measure(String name, Operation operation) {
        for (int i = 0; i < 5; i++) blackhole ^= batch(operation, 2_000);
        double[] nanos = new double[samples];
        for (int sample = 0; sample < samples; sample++) {
            long start = System.nanoTime();
            blackhole ^= batch(operation, operationsPerSample);
            long elapsed = System.nanoTime() - start;
            nanos[sample] = (double) elapsed / operationsPerSample;
        }
        Arrays.sort(nanos);
        double median = percentile(nanos, 0.50);
        return new Measurement(name, operationsPerSample, samples, nanos[0], median,
                percentile(nanos, 0.95), nanos[nanos.length - 1], median == 0 ? 0 : 1_000_000_000d / median);
    }

    private static long batch(Operation operation, int count) {
        long result = 0;
        for (int i = 0; i < count; i++) result += operation.run();
        return result;
    }

    private static double percentile(double[] sorted, double fraction) {
        int index = Math.min(sorted.length - 1, (int) Math.ceil(fraction * sorted.length) - 1);
        return sorted[Math.max(0, index)];
    }
}
