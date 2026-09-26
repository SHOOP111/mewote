package io.lattice.skein;

import io.lattice.warp.SubjectId;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Per-subject AES-GCM keys. Shredding deletes the key while preserving event hashes and ciphertext. */
public final class CryptoShredder {
    private static final int NONCE_BYTES = 12;
    private final ConcurrentMap<String, SecretKey> keys = new ConcurrentHashMap<>();
    private final SecureRandom random;

    public CryptoShredder() { this(new SecureRandom()); }
    public CryptoShredder(SecureRandom random) { this.random = random; }

    public SealedPayload seal(SubjectId subject, String plaintext) {
        try {
            SecretKey key = keys.computeIfAbsent(subject.toString(), ignored -> newKey());
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(subject.toString().getBytes(StandardCharsets.UTF_8));
            return new SealedPayload(nonce, cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("State payload encryption failed", failure);
        }
    }

    public Optional<String> open(SubjectId subject, SealedPayload payload) {
        SecretKey key = keys.get(subject.toString());
        if (key == null) return Optional.empty();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, payload.nonceInternal()));
            cipher.updateAAD(subject.toString().getBytes(StandardCharsets.UTF_8));
            return Optional.of(new String(cipher.doFinal(payload.ciphertextInternal()), StandardCharsets.UTF_8));
        } catch (Exception tampered) {
            throw new SecurityException("Ledger payload authentication failed", tampered);
        }
    }

    public boolean shred(SubjectId subject) { return keys.remove(subject.toString()) != null; }
    public boolean hasKey(SubjectId subject) { return keys.containsKey(subject.toString()); }
    public int keyCount() { return keys.size(); }

    private SecretKey newKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256, random);
            return generator.generateKey();
        } catch (Exception failure) {
            throw new IllegalStateException("AES-256 is required by the Java runtime", failure);
        }
    }
}
