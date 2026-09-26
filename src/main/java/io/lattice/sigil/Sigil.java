package io.lattice.sigil;

import io.lattice.warp.Action;
import io.lattice.warp.ActionPattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Scoped, signed, expiring capability tokens with bounded delegation and local instant revocation. */
public final class Sigil {
    public record Claims(String tokenId, String issuer, String holder, String audience,
                         List<ActionPattern> scopes, Instant expiresAt, boolean delegable, int delegationDepth) {
        public Claims { scopes = List.copyOf(scopes); }
        public boolean permits(Action action) { return scopes.stream().anyMatch(scope -> scope.matches(action)); }
    }

    private final byte[] signingKey;
    private final SecureRandom random;
    private final ConcurrentMap<String, Boolean> revoked = new ConcurrentHashMap<>();

    public Sigil(byte[] signingKey) { this(signingKey, new SecureRandom()); }
    public Sigil(byte[] signingKey, SecureRandom random) {
        Objects.requireNonNull(signingKey, "signingKey");
        if (signingKey.length < 32) throw new IllegalArgumentException("Sigil signing key must contain at least 256 bits");
        this.signingKey = signingKey.clone();
        this.random = Objects.requireNonNull(random, "random");
    }

    public String issue(String issuer, String holder, String audience, List<ActionPattern> scopes,
                        Instant expiresAt, boolean delegable, int delegationDepth) {
        validatePrincipal(issuer, "issuer");
        validatePrincipal(holder, "holder");
        validateAudience(audience);
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (scopes == null || scopes.isEmpty()) throw new IllegalArgumentException("Capability token requires a non-empty scope");
        if (delegationDepth < 0 || delegationDepth > 16) throw new IllegalArgumentException("Delegation depth must be between 0 and 16");
        if (!delegable && delegationDepth != 0) throw new IllegalArgumentException("Non-delegable token must have depth zero");
        byte[] idBytes = new byte[16];
        random.nextBytes(idBytes);
        String id = UUID.nameUUIDFromBytes(idBytes).toString();
        return sign(new Claims(id, issuer, holder, audience, scopes, expiresAt, delegable, delegationDepth));
    }

    public String delegate(String parentToken, String newHolder, List<ActionPattern> requestedScopes,
                           Instant expiresAt, Instant now, String audience) {
        Claims parent = verify(parentToken, now, audience, null);
        if (!now.isBefore(expiresAt)) throw new SecurityException("Delegated token expiry must be in the future");
        if (!parent.delegable() || parent.delegationDepth() < 1) throw new SecurityException("Capability token is not delegable");
        validatePrincipal(newHolder, "holder");
        if (expiresAt.isAfter(parent.expiresAt())) throw new SecurityException("Delegated token cannot outlive its parent");
        for (ActionPattern requested : requestedScopes) {
            if (parent.scopes().stream().noneMatch(requested::isSubsetOf)) {
                throw new SecurityException("Delegation would expand capability scope: " + requested);
            }
        }
        return issue(parent.holder(), newHolder, parent.audience(), requestedScopes, expiresAt,
                parent.delegationDepth() > 1, Math.max(0, parent.delegationDepth() - 1));
    }

    public Claims verify(String token, Instant now, String expectedAudience, Action requestedAction) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(now, "now");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) throw new SecurityException("Malformed capability token");
        byte[] payload;
        byte[] signature;
        try {
            payload = Base64.getUrlDecoder().decode(parts[0]);
            signature = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException malformed) {
            throw new SecurityException("Malformed capability token encoding");
        }
        if (!MessageDigest.isEqual(hmac(payload), signature)) throw new SecurityException("Capability token signature is invalid");
        Claims claims = decode(payload);
        if (revoked.containsKey(claims.tokenId())) throw new SecurityException("Capability token has been revoked");
        if (!now.isBefore(claims.expiresAt())) throw new SecurityException("Capability token has expired");
        if (!claims.audience().equals(expectedAudience)) throw new SecurityException("Capability token audience does not match");
        if (requestedAction != null && !claims.permits(requestedAction)) throw new SecurityException("Action is outside capability token scope");
        return claims;
    }

    public void revoke(String token, Instant now, String audience) {
        Claims claims = verify(token, now, audience, null);
        revoked.put(claims.tokenId(), Boolean.TRUE);
    }

    public boolean isRevoked(String tokenId) { return revoked.containsKey(tokenId); }

    private String sign(Claims claims) {
        byte[] payload = encode(claims);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payload));
    }

    private byte[] encode(Claims claims) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(1);
            out.writeUTF(claims.tokenId());
            out.writeUTF(claims.issuer());
            out.writeUTF(claims.holder());
            out.writeUTF(claims.audience());
            out.writeLong(claims.expiresAt().getEpochSecond());
            out.writeBoolean(claims.delegable());
            out.writeInt(claims.delegationDepth());
            out.writeInt(claims.scopes().size());
            for (ActionPattern scope : claims.scopes()) out.writeUTF(scope.source());
            out.flush();
            return bytes.toByteArray();
        } catch (Exception impossible) {
            throw new IllegalArgumentException("Capability claims could not be encoded", impossible);
        }
    }

    private Claims decode(byte[] payload) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readInt() != 1) throw new SecurityException("Unsupported capability token version");
            String tokenId = in.readUTF();
            String issuer = in.readUTF();
            String holder = in.readUTF();
            String audience = in.readUTF();
            Instant expires = Instant.ofEpochSecond(in.readLong());
            boolean delegable = in.readBoolean();
            int depth = in.readInt();
            int count = in.readInt();
            if (count < 1 || count > 256) throw new SecurityException("Invalid capability scope count");
            List<ActionPattern> scopes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) scopes.add(ActionPattern.parse(in.readUTF()));
            if (in.available() != 0) throw new SecurityException("Unexpected trailing capability data");
            return new Claims(tokenId, issuer, holder, audience, scopes, expires, delegable, depth);
        } catch (SecurityException security) {
            throw security;
        } catch (Exception malformed) {
            throw new SecurityException("Malformed capability token claims");
        }
    }

    private byte[] hmac(byte[] bytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(bytes);
        } catch (Exception impossible) {
            throw new IllegalStateException("HmacSHA256 is required by the Java runtime", impossible);
        }
    }

    private static void validatePrincipal(String principal, String field) {
        if (principal == null || principal.isBlank() || principal.contains("\n")) throw new IllegalArgumentException(field + " is required");
    }
    private static void validateAudience(String audience) {
        if (audience == null || !audience.matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}")) throw new IllegalArgumentException("Invalid token audience");
    }
}
