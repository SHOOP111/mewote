package io.lattice.thread;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** Stateless, HMAC-authenticated, scoped and expiring explanation links. */
public final class ExplanationLinkService {
    public enum Scope { PUBLIC, STAFF, AUDIT }
    public record VerifiedLink(String recordDigest, String subjectId, Instant expiresAt, Scope scope) { }

    private final byte[] signingKey;

    public ExplanationLinkService(byte[] signingKey) {
        Objects.requireNonNull(signingKey, "signingKey");
        if (signingKey.length < 32) throw new IllegalArgumentException("Link-signing key must contain at least 256 bits");
        this.signingKey = signingKey.clone();
    }

    public String issue(ReasoningRecord record, Scope scope, Instant expiresAt) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(expiresAt, "expiresAt");
        String payload = record.digest() + "\n" + record.subjectId() + "\n" + expiresAt.getEpochSecond() + "\n" + scope.name();
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes);
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payloadBytes));
        return body + "." + signature;
    }

    public VerifiedLink verify(String token, Instant now, String viewerSubjectId, Scope maximumScope) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(maximumScope, "maximumScope");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) throw new SecurityException("Malformed explanation link");
        byte[] body;
        byte[] provided;
        try {
            body = Base64.getUrlDecoder().decode(parts[0]);
            provided = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException invalid) {
            throw new SecurityException("Malformed explanation link encoding");
        }
        if (!MessageDigest.isEqual(hmac(body), provided)) throw new SecurityException("Explanation link signature is invalid");
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\n", -1);
        if (fields.length != 4) throw new SecurityException("Malformed explanation link payload");
        Instant expiresAt;
        Scope scope;
        try {
            expiresAt = Instant.ofEpochSecond(Long.parseLong(fields[2]));
            scope = Scope.valueOf(fields[3]);
        } catch (RuntimeException invalid) {
            throw new SecurityException("Malformed explanation link claims");
        }
        if (!now.isBefore(expiresAt)) throw new SecurityException("Explanation link has expired");
        if (scope.ordinal() > maximumScope.ordinal()) throw new SecurityException("Viewer is not permitted the link's disclosure scope");
        if (scope == Scope.PUBLIC && !Objects.equals(viewerSubjectId, fields[1])) {
            throw new SecurityException("Public explanation link is scoped to another subject");
        }
        return new VerifiedLink(fields[0], fields[1], expiresAt, scope);
    }

    private byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception impossible) {
            throw new IllegalStateException("HmacSHA256 is required by the Java runtime", impossible);
        }
    }
}
