package io.lattice.shuttle;

import io.lattice.warp.ActionPattern;
import io.lattice.warp.RuleEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Review-first migration boundary. Importers produce a diff; they never mutate the active policy. */
public final class Shuttle {
    public record LegacyEntry(String principal, String permission, boolean granted, String context) { }
    public record Candidate(String principal, String permission, RuleEffect effect, String context) { }
    public record Gap(String code, String source, String explanation) { }
    public record MigrationPlan(String sourceSystem, List<Candidate> candidates, List<Gap> gaps) {
        public MigrationPlan { candidates = List.copyOf(candidates); gaps = List.copyOf(gaps); }
        public boolean requiresReview() { return !gaps.isEmpty() || candidates.stream().anyMatch(c -> c.permission().contains("*")); }
    }

    public MigrationPlan importLegacy(String sourceSystem, List<LegacyEntry> entries) {
        List<Candidate> candidates = new ArrayList<>();
        List<Gap> gaps = new ArrayList<>();
        for (LegacyEntry entry : entries) {
            String principal = entry.principal() == null ? "" : entry.principal().trim();
            String permission = entry.permission() == null ? "" : entry.permission().trim().toLowerCase(java.util.Locale.ROOT);
            try { ActionPattern.parse(permission); }
            catch (IllegalArgumentException invalid) {
                gaps.add(new Gap("INVALID_PERMISSION_NODE", permission, "Node cannot be represented by Heddle v1: " + invalid.getMessage()));
                continue;
            }
            if (principal.isEmpty()) {
                gaps.add(new Gap("MISSING_STABLE_PRINCIPAL", entry.permission(), "Legacy principal is missing; display names cannot be used as LATTICE subject identities."));
                continue;
            }
            if (!(principal.startsWith("group:") || principal.startsWith("role:")
                    || principal.matches("java_uuid:[0-9a-fA-F-]{36}")
                    || principal.matches("bedrock_xuid:[0-9]{1,32}")
                    || principal.matches("service_account:[a-z0-9][a-z0-9._-]{0,127}"))) {
                gaps.add(new Gap("MUTABLE_PRINCIPAL_REVIEW", principal, "Principal is not a declared role or canonical stable identity. Names cannot be used for authorization."));
                continue;
            }
            if (permission.equals("*") || permission.equals("**") || permission.contains(".**")) {
                gaps.add(new Gap("WILDCARD_SEMANTIC_GAP", permission, "Legacy wildcard meaning may differ from segment-addressed Heddle semantics; verify its complete blast radius."));
            }
            if (entry.context() != null && !entry.context().isBlank()) {
                gaps.add(new Gap("CONTEXT_REVIEW", permission, "Legacy context expression requires an explicit Heddle guard; importer preserved the source expression for review."));
            }
            candidates.add(new Candidate(principal, permission, entry.granted() ? RuleEffect.ALLOW : RuleEffect.DENY,
                    entry.context() == null ? "" : entry.context()));
        }
        return new MigrationPlan(sourceSystem, candidates, gaps);
    }

    /** Stable machine-readable summary for a human approval step. */
    public String diff(MigrationPlan plan) {
        StringBuilder text = new StringBuilder("Migration review — ").append(plan.sourceSystem()).append('\n')
                .append("Candidates: ").append(plan.candidates().size()).append(" | semantic gaps: ").append(plan.gaps().size()).append('\n');
        plan.candidates().stream().sorted(java.util.Comparator.comparing(Candidate::principal).thenComparing(Candidate::permission))
                .forEach(candidate -> text.append(candidate.effect()).append(' ').append(candidate.principal()).append(' ')
                        .append(candidate.permission()).append(candidate.context().isBlank() ? "" : " context=" + candidate.context()).append('\n'));
        for (Gap gap : plan.gaps()) text.append("REVIEW ").append(gap.code()).append(" [").append(gap.source()).append("]: ").append(gap.explanation()).append('\n');
        return text.toString();
    }
}
