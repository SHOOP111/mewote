package io.lattice;

import io.lattice.bastion.Bastion;
import io.lattice.beacon.Doctor;
import io.lattice.beacon.MetricRegistry;
import io.lattice.bridge.Bridge;
import io.lattice.quanta.BudgetService;
import io.lattice.quanta.BudgetSpec;
import io.lattice.skein.DecisionJournal;
import io.lattice.shuttle.Shuttle;
import io.lattice.vigil.Vigil;
import io.lattice.warp.Action;
import io.lattice.warp.ActionPattern;
import io.lattice.warp.DecisionEngine;
import io.lattice.warp.DecisionRequest;
import io.lattice.warp.EvaluationContext;
import io.lattice.warp.SubjectId;
import io.lattice.warp.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.lattice.TestSupport.NOW;
import static io.lattice.TestSupport.SUBJECT;
import static io.lattice.TestSupport.compile;
import static io.lattice.TestSupport.rule;
import static org.junit.jupiter.api.Assertions.*;

class OperationalSubsystemsTest {
    @Test void vigilFreezesBaselineViolationsAndRecordsDismissalReasons() {
        Vigil vigil = new Vigil(new Vigil.Config(2, Duration.ofMinutes(10), 3, 2,
                ZoneId.of("UTC"), LocalTime.of(8, 0), LocalTime.of(18, 0)));
        AtomicInteger notifications = new AtomicInteger();
        vigil.onAlert(alert -> notifications.incrementAndGet());
        var first = new Vigil.GrantEvent("e1", "actor", "subject-1", "server", 1,
                Vigil.Operation.GRANT, NOW, true, false);
        assertFalse(vigil.inspect(first).frozen());
        var second = new Vigil.GrantEvent("e2", "actor", "subject-1", "global", 4,
                Vigil.Operation.GRANT, NOW.plusSeconds(1), false, true);
        var inspection = vigil.inspect(second);
        assertTrue(inspection.frozen());
        assertTrue(vigil.isFrozen("actor"));
        assertTrue(inspection.alerts().stream().anyMatch(a -> a.code() == Vigil.Code.BASELINE_VIOLATION));
        assertTrue(inspection.alerts().stream().anyMatch(a -> a.code() == Vigil.Code.GRANT_BURST));
        assertTrue(inspection.alerts().stream().anyMatch(a -> a.code() == Vigil.Code.DORMANT_ACCOUNT_REVIVAL));
        assertTrue(inspection.alerts().stream().anyMatch(a -> a.code() == Vigil.Code.SCOPE_CREEP));
        assertTrue(notifications.get() >= inspection.alerts().size());
        var alert = inspection.alerts().getFirst();
        assertThrows(IllegalArgumentException.class, () -> vigil.dismiss(alert.id(), "  "));
        assertEquals("reviewed with incident ticket 17", vigil.dismiss(alert.id(), "reviewed with incident ticket 17").dismissalReason());
        assertTrue(vigil.unfreeze("actor", "security lead cleared the event"));
        assertTrue(vigil.freezeHistory("actor").getLast().contains("security lead"));
    }

    @Test void vigilDetectsOffHoursAndRequiresAuditedUnfreeze() {
        Vigil vigil = new Vigil(new Vigil.Config(9, Duration.ofMinutes(5), 9, 9,
                ZoneId.of("UTC"), LocalTime.of(8, 0), LocalTime.of(18, 0)));
        Instant offHours = Instant.parse("2026-01-15T23:00:00Z");
        var event = new Vigil.GrantEvent("e", "actor", "subject", "global", 1,
                Vigil.Operation.GRANT, offHours, true, false);
        assertTrue(vigil.inspect(event).alerts().stream().anyMatch(a -> a.code() == Vigil.Code.OFF_HOURS_CHANGE));
        assertThrows(IllegalArgumentException.class, () -> vigil.unfreeze("actor", ""));
    }

    @Test void bastionRequiresRegistrationScopesAndRateLimit() {
        BudgetService budgets = new BudgetService(() -> 0L);
        Bastion bastion = new Bastion(budgets);
        assertThrows(SecurityException.class, () -> bastion.register("worldedit", List.of(ActionPattern.parse("plugin.edit")),
                new BudgetSpec(1, 1, Duration.ofMinutes(1)), false));
        var registration = bastion.register("worldedit", List.of(ActionPattern.parse("plugin.edit")),
                new BudgetSpec(1, 1, Duration.ofMinutes(1)), true);
        assertTrue(bastion.registered("worldedit"));
        var principal = bastion.authenticate("worldedit", registration.apiKey(), Action.of("plugin.edit"), true);
        assertEquals("worldedit", principal.pluginId());
        assertTrue(principal.mutating());
        assertThrows(SecurityException.class, () -> bastion.authenticate("worldedit", registration.apiKey(), Action.of("plugin.grant.admin"), true));
        assertThrows(Bastion.RateLimitException.class, () -> bastion.authenticate("worldedit", registration.apiKey(), Action.of("plugin.edit"), true));
        assertThrows(SecurityException.class, () -> bastion.authenticate("worldedit", "wrong-key", Action.of("plugin.edit"), false));
        assertThrows(SecurityException.class, () -> bastion.unregister("worldedit", false));
        bastion.unregister("worldedit", true);
        assertFalse(bastion.registered("worldedit"));
    }

    @Test void beaconMetricsAreBoundedAndDoctorReportsFailuresAsData() {
        MetricRegistry metrics = new MetricRegistry(3);
        metrics.increment("lattice.decisions");
        metrics.add("lattice.denials", 2);
        metrics.observeNanos("warp.resolve", 500);
        assertEquals(1, metrics.counters().get("lattice.decisions"));
        assertEquals(2, metrics.counters().get("lattice.denials"));
        assertEquals(1, metrics.histograms().get("warp.resolve").count());
        assertThrows(IllegalStateException.class, () -> metrics.increment("overflow.series"));
        Doctor doctor = new Doctor();
        doctor.register("ledger-chain", () -> new Doctor.CheckResult(Doctor.Status.PASS, "verified"));
        doctor.register("cross-node-drift", () -> { throw new IllegalStateException("peer unreachable"); });
        var report = doctor.run(NOW);
        assertFalse(report.healthy());
        assertEquals(Doctor.Status.FAIL, report.checks().get(1).status());
        assertTrue(report.checks().get(1).evidence().contains("peer unreachable"));
    }

    @Test void decisionJournalIsFilteredSeparatelyFromAuthoritativeState() {
        DecisionEngine engine = new DecisionEngine(compile("{}", rule("sensitive", "lattice.admin.inspect", "DENY", "EXPLICIT", "NETWORK", "")).policy());
        var decision = engine.evaluate(DecisionRequest.forSubject(SUBJECT, "lattice.admin.inspect", Set.of(), EvaluationContext.empty(), NOW));
        DecisionJournal journal = new DecisionJournal();
        assertTrue(journal.record(decision.record(), NOW, false, false, 0));
        assertEquals(DecisionJournal.Cause.SENSITIVE_DENIAL, journal.entries().getFirst().cause());
        assertTrue(journal.forSubject(SUBJECT.toString(), NOW.minusSeconds(1), NOW.plusSeconds(1)).size() == 1);
        assertTrue(journal.record(decision.record(), NOW, false, false, 0), "sensitive denials are always captured");
        journal.capture(SUBJECT.toString(), NOW.minusSeconds(1), NOW.plusSeconds(10));
        var ordinary = new DecisionEngine(compile("{}", rule("allow", "game.play", "ALLOW", "EXPLICIT", "NETWORK", "")).policy())
                .evaluate(DecisionRequest.forSubject(SUBJECT, "game.play", Set.of(), EvaluationContext.empty(), NOW));
        assertTrue(journal.record(ordinary.record(), NOW, false, false, 0));
        assertEquals(DecisionJournal.Cause.ON_DEMAND, journal.entries().getLast().cause());
    }

    @Test void shuttleProducesReviewDiffAndNeverAppliesIt() {
        Shuttle shuttle = new Shuttle();
        var plan = shuttle.importLegacy("legacy-perms", List.of(
                new Shuttle.LegacyEntry("group:member", "minecraft.build.*", true, "world=survival"),
                new Shuttle.LegacyEntry("Marcus", "bad..node", true, ""),
                new Shuttle.LegacyEntry("Marcus", "minecraft.chat.send", true, ""),
                new Shuttle.LegacyEntry("", "minecraft.chat.send", false, "")));
        assertEquals(1, plan.candidates().size());
        assertTrue(plan.requiresReview());
        assertTrue(plan.gaps().stream().anyMatch(gap -> gap.code().equals("CONTEXT_REVIEW")));
        assertTrue(plan.gaps().stream().anyMatch(gap -> gap.code().equals("INVALID_PERMISSION_NODE")));
        assertTrue(plan.gaps().stream().anyMatch(gap -> gap.code().equals("MISSING_STABLE_PRINCIPAL")));
        assertTrue(plan.gaps().stream().anyMatch(gap -> gap.code().equals("MUTABLE_PRINCIPAL_REVIEW")));
        String diff = shuttle.diff(plan);
        assertTrue(diff.contains("Migration review"));
        assertTrue(diff.contains("REVIEW"));
    }

    @Test void bridgeBulkChecksAndObserversDoNotChangeTheVerdict() {
        DecisionEngine engine = new DecisionEngine(compile("{}", rule("allowed", "game.play", "ALLOW", "EXPLICIT", "NETWORK", "")).policy());
        Bridge bridge = new Bridge(engine);
        AtomicInteger observed = new AtomicInteger();
        bridge.observe(decision -> observed.incrementAndGet());
        var request = DecisionRequest.forSubject(SUBJECT, "game.play", Set.of(), EvaluationContext.empty(), NOW);
        assertTrue(bridge.hasPermission(SUBJECT, Action.of("game.play"), request));
        var results = bridge.checkBulk(List.of(request, DecisionRequest.forSubject(SUBJECT, "game.other", Set.of(), EvaluationContext.empty(), NOW)));
        assertEquals(List.of(Verdict.ALLOW, Verdict.DENY), results.stream().map(result -> result.verdict()).toList());
        assertEquals(3, observed.get());
    }
}
