package io.lattice;

import io.lattice.ply.IdentityRisk;
import io.lattice.ply.PlyRegistry;
import io.lattice.quanta.BudgetService;
import io.lattice.quanta.BudgetSpec;
import io.lattice.replay.ReplayEngine;
import io.lattice.sigil.Sigil;
import io.lattice.skein.CryptoShredder;
import io.lattice.skein.StateLedger;
import io.lattice.tessellation.Point2;
import io.lattice.tessellation.Region;
import io.lattice.tessellation.RegionIndex;
import io.lattice.tessellation.WorldOverride;
import io.lattice.weft.HlcTimestamp;
import io.lattice.weft.Mutation;
import io.lattice.weft.PropagationObservation;
import io.lattice.weft.WeftMerger;
import io.lattice.weft.WeftSnapshot;
import io.lattice.warp.Action;
import io.lattice.warp.ActionPattern;
import io.lattice.warp.DecisionEngine;
import io.lattice.warp.DecisionRequest;
import io.lattice.warp.EvaluationContext;
import io.lattice.warp.RuleEffect;
import io.lattice.warp.SubjectId;
import io.lattice.warp.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.lattice.TestSupport.NOW;
import static io.lattice.TestSupport.SUBJECT;
import static io.lattice.TestSupport.compile;
import static io.lattice.TestSupport.rule;
import static org.junit.jupiter.api.Assertions.*;

class SubsystemLawsTest {
    @Test void budgetReserveIsAtomicConservedAndRefundsExactlyOnce() {
        AtomicLong time = new AtomicLong(0);
        BudgetService service = new BudgetService(time::get);
        BudgetSpec spec = new BudgetSpec(2, 2, Duration.ofSeconds(10));
        var first = service.reserve("build", SUBJECT, "world:survival", spec);
        var second = service.reserve("build", SUBJECT, "world:survival", spec);
        var exhausted = service.reserve("build", SUBJECT, "world:survival", spec);
        assertTrue(first.reserved());
        assertTrue(second.reserved());
        assertFalse(exhausted.reserved());
        assertEquals(Duration.ofSeconds(10), exhausted.retryAfter());
        assertTrue(first.lease().commit());
        assertFalse(first.lease().release());
        assertTrue(second.lease().release());
        assertFalse(second.lease().release());
        var accounting = service.accounting("build", SUBJECT, "world:survival");
        assertTrue(accounting.conserved());
        assertTrue(accounting.withinIssued());
        assertEquals(1, accounting.available());
        assertEquals(1, accounting.spent());
        assertEquals(0, accounting.reserved());
        var finalLease = service.reserve("build", SUBJECT, "world:survival", spec).lease();
        finalLease.close();
        assertTrue(service.accounting("build", SUBJECT, "world:survival").conserved());
    }

    @Test void concurrentBudgetChecksNeverIssueMoreLeasesThanCapacity() throws Exception {
        int capacity = 31;
        BudgetService service = new BudgetService(() -> 0L);
        BudgetSpec spec = new BudgetSpec(capacity, 1, Duration.ofHours(1));
        AtomicInteger successes = new AtomicInteger();
        int workers = 200;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<BudgetService.Reservation> leases = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < workers; i++) pool.submit(() -> {
            start.await();
            var reservation = service.reserve("wand", SUBJECT, "region:spawn", spec);
            if (reservation.reserved()) { successes.incrementAndGet(); leases.add(reservation); }
            return null;
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(capacity, successes.get());
        leases.forEach(reservation -> reservation.lease().commit());
        var accounting = service.accounting("wand", SUBJECT, "region:spawn");
        assertEquals(capacity, accounting.spent());
        assertTrue(accounting.withinIssued());
        assertTrue(accounting.conserved());
    }

    @Test void identityNameChangesNeverChangeTheAuthorizationKey() {
        PlyRegistry ply = new PlyRegistry();
        UUID uuid = UUID.fromString("22222222-2222-4222-8222-222222222222");
        var first = ply.registerJava(uuid, "Marcus", true, NOW);
        var renamed = ply.registerJava(uuid, "Marcus_2", false, NOW.plusSeconds(60));
        assertEquals(first.subjectId(), renamed.subjectId());
        assertEquals(IdentityRisk.OFFLINE_MODE_UNVERIFIED, renamed.risk());
        assertEquals(List.of("Marcus", "Marcus_2"), renamed.nameHistory().stream().map(change -> change.name()).toList());
        assertTrue(ply.resolveDisplayName("Marcus").isEmpty());
        assertEquals(first.subjectId(), ply.resolveJava(uuid).orElseThrow());
    }

    @Test void javaBedrockLinkRequiresProofAndKeepsBothStableAliases() {
        PlyRegistry ply = new PlyRegistry();
        UUID javaUuid = UUID.fromString("33333333-3333-4333-8333-333333333333");
        var javaIdentity = ply.registerJava(javaUuid, "JavaName", false, NOW);
        var bedrockIdentity = ply.registerBedrock("987654321", "BedrockName", NOW);
        assertThrows(SecurityException.class, () -> ply.linkBedrockToJava(javaUuid, "987654321", false));
        var linked = ply.linkBedrockToJava(javaUuid, "987654321", true);
        assertEquals(javaIdentity.subjectId(), linked.subjectId());
        assertEquals(javaIdentity.subjectId(), ply.resolveBedrock("987654321").orElseThrow());
        assertNotEquals(javaIdentity.subjectId(), bedrockIdentity.subjectId());
    }

    @Test void tessellationFastRejectsByWorldAndBoundsAndIncludesEdges() {
        Region spawn = new Region("spawn", "world-1", List.of(new Point2(0, 0), new Point2(10, 0),
                new Point2(10, 10), new Point2(0, 10)), Set.of("market"));
        Region negative = new Region("negative", "world-1", List.of(new Point2(-70, -70), new Point2(-60, -70),
                new Point2(-60, -60), new Point2(-70, -60)), Set.of());
        RegionIndex index = new RegionIndex("snapshot-42", List.of(spawn, negative),
                List.of(new WorldOverride("world-1", "minecraft.build.**", RuleEffect.DENY, "world-policy")));
        assertEquals(List.of("spawn"), index.at("world-1", 5, 5).stream().map(Region::id).toList());
        assertEquals(List.of("spawn"), index.at("world-1", 10, 10).stream().map(Region::id).toList(), "polygon boundary is included");
        assertTrue(index.at("world-2", 5, 5).isEmpty());
        assertEquals(List.of("negative"), index.at("world-1", -65, -65).stream().map(Region::id).toList());
        assertEquals("market", index.at("world-1", 5, 5).getFirst().portalLinkedZones().iterator().next());
        assertEquals(1, index.overridesFor("world-1").size());
        assertEquals("snapshot-42", index.snapshotId());
        assertTrue(index.cacheSize() >= 4);
        assertThrows(UnsupportedOperationException.class, () -> index.at("world-1", 5, 5).clear());
    }

    @Test void hlcSurvivesWallClockRegressionAndObservesRemoteCausality() {
        var clock = new io.lattice.weft.HybridLogicalClock("node-a");
        HlcTimestamp first = clock.tick(Instant.ofEpochMilli(1000));
        HlcTimestamp skewed = clock.tick(Instant.ofEpochMilli(900));
        assertEquals(1000, skewed.physicalMillis());
        assertEquals(first.logical() + 1, skewed.logical());
        HlcTimestamp received = clock.receive(new HlcTimestamp(2000, 9, "node-b"), Instant.ofEpochMilli(500));
        HlcTimestamp next = clock.tick(Instant.ofEpochMilli(400));
        assertTrue(next.compareTo(received) > 0);
    }

    @Test void weftMergeIsIdempotentDeterministicAndRevocationMonotonic() {
        WeftMerger merger = new WeftMerger();
        var capability = ActionPattern.parse("minecraft.build.place");
        Mutation grant = mutation("g1", Mutation.Kind.GRANT, 10, false, capability);
        Mutation revoke = mutation("r1", Mutation.Kind.REVOKE, 20, false, capability);
        Mutation staleGrant = mutation("g2", Mutation.Kind.GRANT, 30, false, capability);
        var result = merger.merge(new WeftSnapshot(Map.of(), Map.of()), List.of(staleGrant, grant, revoke), ignored -> true);
        assertEquals(WeftMerger.Status.APPLIED, result.status());
        assertFalse(result.snapshot().allows(SUBJECT + "|minecraft.build.place"));
        assertEquals(WeftMerger.Status.NO_CHANGE, merger.merge(result.snapshot(), List.of(grant, revoke, staleGrant), ignored -> true).status());
        Mutation approved = mutation("g3", Mutation.Kind.GRANT, 40, true, capability);
        var regrant = merger.merge(result.snapshot(), List.of(approved), ignored -> true);
        assertTrue(regrant.snapshot().allows(SUBJECT + "|minecraft.build.place"));
    }

    @Test void weftQuarantinesInvariantViolationsAndEventIdCollisions() {
        WeftMerger merger = new WeftMerger();
        WeftSnapshot local = new WeftSnapshot(Map.of(), Map.of());
        Mutation grant = mutation("one", Mutation.Kind.GRANT, 1, false, ActionPattern.parse("admin.**"));
        var quarantined = merger.merge(local, List.of(grant), candidate -> candidate.effective().values().stream()
                .noneMatch(value -> value.capability().source().startsWith("admin.")));
        assertEquals(WeftMerger.Status.QUARANTINED, quarantined.status());
        assertSame(local, quarantined.snapshot());
        assertFalse(quarantined.alert().isBlank());
        var accepted = merger.merge(local, List.of(grant), ignored -> true);
        Mutation collision = mutation("one", Mutation.Kind.REVOKE, 2, false, ActionPattern.parse("admin.**"));
        assertEquals(WeftMerger.Status.QUARANTINED, merger.merge(accepted.snapshot(), List.of(collision), ignored -> true).status());
    }

    @Test void propagationMeasurementIsHonestAndBounded() {
        var within = new PropagationObservation(NOW, NOW.plusMillis(1900));
        var outside = new PropagationObservation(NOW, NOW.plusMillis(2100));
        assertTrue(within.meetsDeclaredBound());
        assertFalse(outside.meetsDeclaredBound());
        assertThrows(IllegalArgumentException.class, () -> new PropagationObservation(NOW, NOW.minusSeconds(1)));
    }

    @Test void ledgerReconstructsFromCheckpointAndCryptoShreddingPreservesTheChain() {
        CryptoShredder shredder = new CryptoShredder();
        StateLedger ledger = new StateLedger(shredder);
        HlcTimestamp t1 = new HlcTimestamp(1, 0, "node-a");
        HlcTimestamp t2 = new HlcTimestamp(2, 0, "node-a");
        var first = ledger.set(SUBJECT, "role", "member", t1);
        assertEquals(1, ledger.checkpoint());
        var second = ledger.set(SUBJECT, "role", "moderator", t2);
        assertTrue(ledger.verifyChain());
        String stateKey = SUBJECT + "|role";
        assertEquals("member", ledger.reconstructAt(first.sequence()).values().get(stateKey).value());
        assertEquals("moderator", ledger.reconstructAt(second.sequence()).values().get(stateKey).value());
        String head = ledger.headHash();
        assertTrue(ledger.cryptoShred(SUBJECT));
        var erased = ledger.reconstructAt(second.sequence()).values().get(stateKey);
        assertTrue(erased.erased());
        assertNull(erased.value());
        assertEquals(head, ledger.headHash());
        assertTrue(ledger.verifyChain());
        ledger.delete(SUBJECT, "role", new HlcTimestamp(3, 0, "node-a"));
        assertFalse(ledger.reconstructAt(ledger.events().size()).values().containsKey(stateKey));
    }

    @Test void sigilAuthenticatesScopeExpiryRevocationAndBoundedDelegation() {
        Sigil sigil = new Sigil(new byte[32]);
        Instant expiry = NOW.plusSeconds(300);
        String parent = sigil.issue("owner", "service:builder", "server-a",
                List.of(ActionPattern.parse("minecraft.build.**")), expiry, true, 2);
        var claims = sigil.verify(parent, NOW, "server-a", Action.of("minecraft.build.place"));
        assertEquals("service:builder", claims.holder());
        String child = sigil.delegate(parent, "service:worker", List.of(ActionPattern.parse("minecraft.build.place")),
                NOW.plusSeconds(120), NOW, "server-a");
        assertTrue(sigil.verify(child, NOW.plusSeconds(1), "server-a", Action.of("minecraft.build.place")).permits(Action.of("minecraft.build.place")));
        assertThrows(SecurityException.class, () -> sigil.delegate(parent, "service:worker",
                List.of(ActionPattern.parse("minecraft.admin.**")), NOW.plusSeconds(120), NOW, "server-a"));
        assertThrows(SecurityException.class, () -> sigil.verify(parent, expiry, "server-a", null));
        sigil.revoke(parent, NOW, "server-a");
        assertThrows(SecurityException.class, () -> sigil.verify(parent, NOW.plusSeconds(1), "server-a", null));
    }

    @Test void replayReportsEveryVerdictDifferenceAndAffectedStableSubjects() {
        DecisionEngine live = new DecisionEngine(compile("{}", rule("old", "game.build", "ALLOW", "EXPLICIT", "NETWORK", "")).policy());
        DecisionEngine candidate = new DecisionEngine(compile("{}", rule("new", "game.build", "DENY", "EXPLICIT", "NETWORK", "")).policy());
        DecisionRequest query = DecisionRequest.forSubject(SUBJECT, "game.build", Set.of(), EvaluationContext.empty(), NOW);
        var recorded = live.evaluate(query);
        ReplayEngine replay = new ReplayEngine();
        var report = replay.replay(List.of(new ReplayEngine.RecordedQuery(query, recorded.verdict(), recorded.ruleId())), candidate);
        assertEquals(1, report.verdictChanges());
        assertEquals(List.of(SUBJECT), report.affectedSubjects());
        assertEquals(1, report.transitions().get("ALLOW->DENY"));
        assertFalse(replay.cutoverEligible(report, false));
        assertTrue(replay.cutoverEligible(report, true));
    }

    private static Mutation mutation(String id, Mutation.Kind kind, long physical, boolean approved,
                                     ActionPattern capability) {
        return new Mutation(id, SUBJECT, capability, kind, new HlcTimestamp(physical, 0, "node"), approved);
    }
}
