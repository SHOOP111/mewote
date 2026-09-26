package io.lattice.beacon;

/** Named observability boundary for bounded metrics and doctor checks. */
public final class Beacon {
    private final MetricRegistry metrics;
    private final Doctor doctor;
    public Beacon(MetricRegistry metrics, Doctor doctor) { this.metrics = metrics; this.doctor = doctor; }
    public MetricRegistry metrics() { return metrics; }
    public Doctor doctor() { return doctor; }
}
