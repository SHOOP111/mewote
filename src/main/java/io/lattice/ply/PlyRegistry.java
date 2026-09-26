package io.lattice.ply;

import io.lattice.warp.SubjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Alias registry keyed only by stable Java UUIDs and Bedrock XUIDs. Names are searchable history only. */
public final class PlyRegistry {
    private record StableAlias(SubjectId.Kind kind, String value) { }
    private final Map<StableAlias, SubjectId> aliases = new HashMap<>();
    private final Map<SubjectId, MutableIdentity> identities = new HashMap<>();

    public synchronized PlyIdentity registerJava(UUID uuid, String displayName, boolean offlineMode, Instant at) {
        SubjectId subject = SubjectId.java(uuid);
        bind(new StableAlias(SubjectId.Kind.JAVA_UUID, subject.canonical()), subject);
        MutableIdentity identity = identities.computeIfAbsent(subject,
                ignored -> new MutableIdentity(subject, offlineMode ? IdentityRisk.OFFLINE_MODE_UNVERIFIED : IdentityRisk.NORMAL));
        identity.addName(displayName, at);
        return identity.snapshot();
    }

    public synchronized PlyIdentity registerBedrock(String xuid, String displayName, Instant at) {
        SubjectId subject = SubjectId.bedrock(xuid);
        bind(new StableAlias(SubjectId.Kind.BEDROCK_XUID, subject.canonical()), subject);
        MutableIdentity identity = identities.computeIfAbsent(subject,
                ignored -> new MutableIdentity(subject, IdentityRisk.NORMAL));
        identity.addName(displayName, at);
        return identity.snapshot();
    }

    public synchronized PlyIdentity registerService(String serviceId) {
        SubjectId subject = SubjectId.service(serviceId);
        return identities.computeIfAbsent(subject,
                ignored -> new MutableIdentity(subject, IdentityRisk.SERVICE_ACCOUNT)).snapshot();
    }

    /** Cross-platform aliases can be linked only after an external proof-of-control check. */
    public synchronized PlyIdentity linkBedrockToJava(UUID javaUuid, String xuid, boolean proofVerified) {
        if (!proofVerified) throw new SecurityException("Cross-identity linking requires proof of control for both identities");
        SubjectId java = SubjectId.java(javaUuid);
        SubjectId bedrock = SubjectId.bedrock(xuid);
        MutableIdentity target = identities.get(java);
        MutableIdentity source = identities.get(bedrock);
        if (target == null || source == null) throw new IllegalArgumentException("Both stable identities must already be registered");
        StableAlias bedrockAlias = new StableAlias(SubjectId.Kind.BEDROCK_XUID, bedrock.canonical());
        SubjectId currentlyBound = aliases.get(bedrockAlias);
        if (!bedrock.equals(currentlyBound)) throw new IllegalStateException("Bedrock alias changed during link operation");
        aliases.put(bedrockAlias, java);
        target.names.addAll(source.names);
        identities.remove(bedrock);
        return target.snapshot();
    }

    public synchronized Optional<SubjectId> resolveJava(UUID uuid) {
        return Optional.ofNullable(aliases.get(new StableAlias(SubjectId.Kind.JAVA_UUID, uuid.toString())));
    }

    public synchronized Optional<SubjectId> resolveBedrock(String xuid) {
        return Optional.ofNullable(aliases.get(new StableAlias(SubjectId.Kind.BEDROCK_XUID, xuid)));
    }

    public synchronized Optional<PlyIdentity> find(SubjectId subject) {
        MutableIdentity identity = identities.get(subject);
        return identity == null ? Optional.empty() : Optional.of(identity.snapshot());
    }

    /** Explicitly demonstrates that display names cannot resolve to a subject. */
    public Optional<SubjectId> resolveDisplayName(String ignoredName) { return Optional.empty(); }

    private void bind(StableAlias alias, SubjectId subject) {
        SubjectId existing = aliases.putIfAbsent(alias, subject);
        if (existing != null && !existing.equals(subject)) throw new IllegalStateException("Stable alias is already linked to another subject");
    }

    private static final class MutableIdentity {
        private final SubjectId subject;
        private final IdentityRisk risk;
        private final List<NameChange> names = new ArrayList<>();
        private MutableIdentity(SubjectId subject, IdentityRisk risk) { this.subject = subject; this.risk = risk; }
        private void addName(String name, Instant at) {
            Objects.requireNonNull(at, "at");
            if (name == null || name.isBlank()) return;
            if (names.isEmpty() || !names.get(names.size() - 1).name().equals(name)) names.add(new NameChange(name, at));
        }
        private PlyIdentity snapshot() { return new PlyIdentity(subject, risk, names); }
    }
}
