package io.lattice.skein;

import io.lattice.weft.HlcTimestamp;
import io.lattice.warp.SubjectId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hash-chained event-sourced state prototype. Checkpoints bound reconstruction replay; this version
 * is in-memory and must not be described as durable until a crash-safe storage adapter is added.
 */
public final class StateLedger {
    private static final String GENESIS = "0".repeat(64);
    private final CryptoShredder shredder;
    private final List<StateEvent> events = new ArrayList<>();
    private final TreeMap<Long, Checkpoint> checkpoints = new TreeMap<>();
    private String head = GENESIS;
    private final Map<String, StoredValue> current = new TreeMap<>();

    public StateLedger(CryptoShredder shredder) { this.shredder = shredder; }

    public synchronized StateEvent set(SubjectId subject, String key, String value, HlcTimestamp clock) {
        if (value == null) throw new IllegalArgumentException("Use delete for a null value");
        return append(subject, key, StateEvent.Operation.SET, shredder.seal(subject, value), clock);
    }

    public synchronized StateEvent delete(SubjectId subject, String key, HlcTimestamp clock) {
        return append(subject, key, StateEvent.Operation.DELETE, null, clock);
    }

    private StateEvent append(SubjectId subject, String key, StateEvent.Operation operation,
                              SealedPayload payload, HlcTimestamp clock) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("State key is required");
        long sequence = events.size() + 1L;
        String hash = hash(sequence, clock, subject, key, operation, payload, head);
        StateEvent event = new StateEvent(sequence, clock, subject, key, operation, payload, head, hash);
        events.add(event);
        head = hash;
        String stateKey = stateKey(subject, key);
        if (operation == StateEvent.Operation.DELETE) current.remove(stateKey);
        else current.put(stateKey, new StoredValue(subject, payload));
        return event;
    }

    /** A checkpoint is immutable and includes a digest over its encrypted payload map. */
    public synchronized long checkpoint() {
        long sequence = events.size();
        String chainHead = sequence == 0 ? GENESIS : events.get((int) sequence - 1).hash();
        Map<String, StoredValue> state = Map.copyOf(current);
        String digest = checkpointHash(sequence, chainHead, state);
        checkpoints.put(sequence, new Checkpoint(sequence, chainHead, state, digest));
        return sequence;
    }

    public synchronized StateSnapshot reconstructAt(long sequence) {
        if (sequence < 0 || sequence > events.size()) throw new IllegalArgumentException("Unknown ledger sequence: " + sequence);
        Map.Entry<Long, Checkpoint> entry = checkpoints.floorEntry(sequence);
        long start = 0;
        Map<String, StoredValue> state = new TreeMap<>();
        if (entry != null) {
            Checkpoint checkpoint = entry.getValue();
            if (!checkpointHash(checkpoint.sequence, checkpoint.chainHead, checkpoint.state).equals(checkpoint.digest)) {
                throw new SecurityException("Checkpoint integrity failure at sequence " + checkpoint.sequence);
            }
            start = checkpoint.sequence;
            state.putAll(checkpoint.state);
        }
        for (long currentSequence = start + 1; currentSequence <= sequence; currentSequence++) {
            StateEvent event = events.get((int) currentSequence - 1);
            String key = stateKey(event.subject(), event.key());
            if (event.operation() == StateEvent.Operation.DELETE) state.remove(key);
            else state.put(key, new StoredValue(event.subject(), event.payload()));
        }
        Map<String, StateSnapshot.RecoveredValue> values = new TreeMap<>();
        state.forEach((key, stored) -> {
            var clear = shredder.open(stored.subject, stored.payload);
            values.put(key, clear.map(value -> new StateSnapshot.RecoveredValue(value, false))
                    .orElseGet(() -> new StateSnapshot.RecoveredValue(null, true)));
        });
        return new StateSnapshot(sequence, values);
    }

    public synchronized boolean verifyChain() {
        String previous = GENESIS;
        long expectedSequence = 1;
        for (StateEvent event : events) {
            if (event.sequence() != expectedSequence || !event.previousHash().equals(previous)) return false;
            String expectedHash = hash(event.sequence(), event.clock(), event.subject(), event.key(), event.operation(), event.payload(), previous);
            if (!MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.US_ASCII), event.hash().getBytes(StandardCharsets.US_ASCII))) return false;
            previous = event.hash();
            expectedSequence++;
        }
        for (Checkpoint checkpoint : checkpoints.values()) {
            String expectedHead = checkpoint.sequence == 0 ? GENESIS : events.get((int) checkpoint.sequence - 1).hash();
            if (!expectedHead.equals(checkpoint.chainHead)
                    || !checkpointHash(checkpoint.sequence, checkpoint.chainHead, checkpoint.state).equals(checkpoint.digest)) return false;
        }
        return previous.equals(head);
    }

    /** Deletes only the subject's encryption key. Event hashes remain verifiable. */
    public boolean cryptoShred(SubjectId subject) { return shredder.shred(subject); }
    public synchronized List<StateEvent> events() { return List.copyOf(events); }
    public synchronized int checkpointCount() { return checkpoints.size(); }
    public synchronized String headHash() { return head; }

    private static String stateKey(SubjectId subject, String key) { return subject + "|" + key; }

    private static String hash(long sequence, HlcTimestamp clock, SubjectId subject, String key,
                               StateEvent.Operation operation, SealedPayload payload, String previous) {
        String nonce = payload == null ? "" : Base64.getEncoder().encodeToString(payload.nonceInternal());
        String cipher = payload == null ? "" : Base64.getEncoder().encodeToString(payload.ciphertextInternal());
        String canonical = sequence + "\n" + clock.physicalMillis() + "\n" + clock.logical() + "\n" + encode(clock.nodeId())
                + "\n" + encode(subject.toString()) + "\n" + encode(key) + "\n" + operation + "\n" + nonce + "\n" + cipher + "\n" + previous;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required", impossible); }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String checkpointHash(long sequence, String chainHead, Map<String, StoredValue> state) {
        StringBuilder canonical = new StringBuilder().append(sequence).append('\n').append(chainHead).append('\n');
        new TreeMap<>(state).forEach((key, value) -> canonical.append(encode(key)).append('\n')
                .append(Base64.getEncoder().encodeToString(value.payload.nonceInternal())).append('\n')
                .append(Base64.getEncoder().encodeToString(value.payload.ciphertextInternal())).append('\n'));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required", impossible); }
    }

    private record StoredValue(SubjectId subject, SealedPayload payload) { }
    private record Checkpoint(long sequence, String chainHead, Map<String, StoredValue> state, String digest) { }
}
