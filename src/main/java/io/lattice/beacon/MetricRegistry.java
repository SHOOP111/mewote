package io.lattice.beacon;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/** Bounded-label counters and fixed-bucket latency histograms. */
public final class MetricRegistry {
    public record HistogramSnapshot(long count, long totalNanos, long[] bucketCounts, long[] upperBoundsNanos) {
        public HistogramSnapshot { bucketCounts = bucketCounts.clone(); upperBoundsNanos = upperBoundsNanos.clone(); }
        @Override public long[] bucketCounts() { return bucketCounts.clone(); }
        @Override public long[] upperBoundsNanos() { return upperBoundsNanos.clone(); }
    }
    private final ConcurrentMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Histogram> histograms = new ConcurrentHashMap<>();
    private final int maximumSeries;
    private final java.util.concurrent.atomic.AtomicInteger seriesCount = new java.util.concurrent.atomic.AtomicInteger();

    public MetricRegistry(int maximumSeries) {
        if (maximumSeries < 1) throw new IllegalArgumentException("maximumSeries must be positive");
        this.maximumSeries = maximumSeries;
    }

    public void increment(String name) { counter(name).increment(); }
    public void add(String name, long amount) {
        if (amount < 0) throw new IllegalArgumentException("counter increments cannot be negative");
        counter(name).add(amount);
    }
    public void observeNanos(String name, long nanos) {
        if (nanos < 0) throw new IllegalArgumentException("latency cannot be negative");
        histograms.computeIfAbsent(name, ignored -> {
            reserveSeries();
            return new Histogram();
        }).observe(nanos);
    }

    public Map<String, Long> counters() {
        Map<String, Long> result = new TreeMap<>();
        counters.forEach((key, value) -> result.put(key, value.sum()));
        return java.util.Collections.unmodifiableMap(result);
    }
    public Map<String, HistogramSnapshot> histograms() {
        Map<String, HistogramSnapshot> result = new TreeMap<>();
        histograms.forEach((key, value) -> result.put(key, value.snapshot()));
        return java.util.Collections.unmodifiableMap(result);
    }

    private LongAdder counter(String name) {
        if (name == null || !name.matches("[a-zA-Z_:][a-zA-Z0-9_:]{0,127}")) throw new IllegalArgumentException("Invalid metric name");
        return counters.computeIfAbsent(name, ignored -> {
            reserveSeries();
            return new LongAdder();
        });
    }

    private void reserveSeries() {
        int count = seriesCount.incrementAndGet();
        if (count > maximumSeries) {
            seriesCount.decrementAndGet();
            throw new IllegalStateException("Metric series limit reached");
        }
    }

    private static final class Histogram {
        private static final long[] BOUNDS = {100, 250, 500, 1_000, 2_500, 5_000, 10_000, 25_000, 50_000, 100_000, 1_000_000, Long.MAX_VALUE};
        private final LongAdder count = new LongAdder();
        private final LongAdder total = new LongAdder();
        private final LongAdder[] buckets = Arrays.stream(BOUNDS).mapToObj(ignored -> new LongAdder()).toArray(LongAdder[]::new);
        private void observe(long nanos) {
            count.increment(); total.add(nanos);
            for (int i = 0; i < BOUNDS.length; i++) if (nanos <= BOUNDS[i]) buckets[i].increment();
        }
        private HistogramSnapshot snapshot() {
            return new HistogramSnapshot(count.sum(), total.sum(), Arrays.stream(buckets).mapToLong(LongAdder::sum).toArray(), BOUNDS);
        }
    }
}
