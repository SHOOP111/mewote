package io.lattice.quanta;

import io.lattice.warp.SubjectId;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/** Atomic token buckets keyed by budget class, stable subject, and scope. */
public final class BudgetService {
    public record Reservation(BudgetLease lease, Duration retryAfter) {
        public boolean reserved() { return lease != null; }
    }
    public record Accounting(long issued, long available, long reserved, long spent, long discarded) {
        public boolean conserved() { return issued == available + reserved + spent + discarded; }
        public boolean withinIssued() { return reserved + spent <= issued; }
    }

    private record Key(String budgetClass, SubjectId subject, String scope) { }
    private final ConcurrentMap<Key, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    public BudgetService() { this(System::nanoTime); }
    public BudgetService(LongSupplier nanoTime) { this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime"); }

    public Reservation reserve(String budgetClass, SubjectId subject, String scope, BudgetSpec spec) {
        Objects.requireNonNull(budgetClass, "budgetClass");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(spec, "spec");
        Key key = new Key(budgetClass, subject, scope);
        long now = nanoTime.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(spec, now));
        if (!bucket.spec.equals(spec)) throw new IllegalStateException("Budget specification changed without a versioned policy transition: " + budgetClass);
        synchronized (bucket) {
            bucket.refill(now);
            if (bucket.available == 0) return new Reservation(null, bucket.retryAfter(now));
            bucket.available--;
            bucket.reserved++;
            BudgetLease lease = new BudgetLease(commit -> bucket.finish(commit));
            return new Reservation(lease, Duration.ZERO);
        }
    }

    public Accounting accounting(String budgetClass, SubjectId subject, String scope) {
        Bucket bucket = buckets.get(new Key(budgetClass, subject, scope));
        if (bucket == null) return new Accounting(0, 0, 0, 0, 0);
        synchronized (bucket) { return bucket.accounting(); }
    }

    public int bucketCount() { return buckets.size(); }

    private static final class Bucket {
        private final BudgetSpec spec;
        private final long periodNanos;
        private long lastRefillNanos;
        private long issued;
        private long available;
        private long reserved;
        private long spent;
        private long discarded;

        private Bucket(BudgetSpec spec, long now) {
            this.spec = spec;
            this.periodNanos = spec.refillPeriod().toNanos();
            this.lastRefillNanos = now;
            this.issued = spec.capacity();
            this.available = spec.capacity();
        }

        private void refill(long now) {
            long elapsed = now - lastRefillNanos;
            if (elapsed < periodNanos) return;
            long periods = elapsed / periodNanos;
            long newIssued = saturatingMultiply(periods, spec.refillTokens());
            issued = saturatingAdd(issued, newIssued);
            long newAvailable = Math.min((long) spec.capacity(), saturatingAdd(available, newIssued));
            discarded = saturatingAdd(discarded, newIssued - (newAvailable - available));
            available = newAvailable;
            lastRefillNanos += periods * periodNanos;
        }

        private synchronized boolean finish(boolean commit) {
            // Called under this monitor by the service's returned lease.
            if (reserved <= 0) return false;
            reserved--;
            if (commit) spent++;
            else available++;
            return true;
        }

        private Duration retryAfter(long now) {
            long elapsed = now - lastRefillNanos;
            long until = periodNanos - Math.floorMod(elapsed, periodNanos);
            return Duration.ofNanos(Math.max(1, until));
        }

        private Accounting accounting() {
            return new Accounting(issued, available, reserved, spent, discarded);
        }

        private static long saturatingMultiply(long left, long right) {
            try { return Math.multiplyExact(left, right); }
            catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
        }

        private static long saturatingAdd(long left, long right) {
            try { return Math.addExact(left, right); }
            catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
        }
    }
}
