package io.lattice;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lattice.beacon.BenchmarkHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkHarnessTest {
    @Test void reportSeparatesFastContextSpatialAndAuditPathsAndLabelsItsLimits() throws Exception {
        var report = new BenchmarkHarness(1_000, 5).run();
        assertEquals(4, report.measurements().size());
        assertEquals("fast_pattern_match", report.measurements().getFirst().path());
        assertTrue(report.measurements().stream().anyMatch(m -> m.path().equals("context_guard_and_atomic_budget")));
        assertTrue(report.measurements().stream().anyMatch(m -> m.path().equals("spatial_index_lookup")));
        assertTrue(report.measurements().stream().anyMatch(m -> m.path().equals("audit_record_render_and_journal")));
        assertTrue(report.methodology().contains("no target is enforced or claimed achieved"));
        var parsed = new ObjectMapper().readTree(report.toJson());
        assertEquals(4, parsed.path("measurements").size());
        assertTrue(parsed.path("measurements").get(0).path("medianOperationsPerSecond").asDouble() > 0);
    }
}
