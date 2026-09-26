package io.lattice.bastion;

import io.lattice.quanta.BudgetService;
import io.lattice.quanta.BudgetSpec;
import io.lattice.sigil.Sigil;
import io.lattice.warp.Action;
import io.lattice.warp.ActionPattern;
import io.lattice.warp.SubjectId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit plugin registration, mutation attribution, scoped credentials, and rate limiting. */
public final class Bastion {
    public record Registration(String pluginId, String apiKey, List<ActionPattern> mutationScopes) { }
    public record Principal(String pluginId, String serviceSubject, boolean mutating) { }
    private record Plugin(String digest, List<ActionPattern> mutationScopes, BudgetSpec rateLimit) { }

    private final Map<String, Plugin> plugins = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final BudgetService limits;

    public Bastion(BudgetService limits) { this.limits = limits; }

    public Registration register(String pluginId, List<ActionPattern> mutationScopes, BudgetSpec rateLimit,
                                 boolean registeringActorAuthorized) {
        if (!registeringActorAuthorized) throw new SecurityException("Only an authorized administrator may register a mutating plugin");
        if (pluginId == null || !pluginId.matches("[a-z0-9][a-z0-9._-]{0,127}")) throw new IllegalArgumentException("Invalid plugin ID");
        if (mutationScopes == null) throw new IllegalArgumentException("Mutation scopes must be explicit (use an empty list for no writes)");
        byte[] secretBytes = new byte[32]; random.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        String digest = digest(secret);
        Plugin plugin = new Plugin(digest, List.copyOf(mutationScopes), rateLimit);
        if (plugins.putIfAbsent(pluginId, plugin) != null) throw new IllegalStateException("Plugin is already registered");
        return new Registration(pluginId, secret, plugin.mutationScopes());
    }

    public Principal authenticate(String pluginId, String apiKey, Action action, boolean mutating) {
        Plugin plugin = plugins.get(pluginId);
        if (plugin == null || !MessageDigest.isEqual(plugin.digest().getBytes(StandardCharsets.US_ASCII), digest(apiKey).getBytes(StandardCharsets.US_ASCII))) {
            throw new SecurityException("Plugin authentication failed");
        }
        if (mutating && plugin.mutationScopes().stream().noneMatch(scope -> scope.matches(action))) {
            throw new SecurityException("Plugin is not registered for this mutation scope");
        }
        SubjectId service = SubjectId.service("plugin." + pluginId);
        var reservation = limits.reserve("bastion:" + pluginId, service, "api", plugin.rateLimit());
        if (!reservation.reserved()) throw new RateLimitException(reservation.retryAfter());
        reservation.lease().commit();
        return new Principal(pluginId, service.toString(), mutating);
    }

    public void unregister(String pluginId, boolean registeringActorAuthorized) {
        if (!registeringActorAuthorized) throw new SecurityException("Only an authorized administrator may unregister a plugin");
        plugins.remove(pluginId);
    }

    public boolean registered(String pluginId) { return plugins.containsKey(pluginId); }

    private static String digest(String input) {
        if (input == null) return "";
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 is required", impossible); }
    }

    public static final class RateLimitException extends SecurityException {
        private final Duration retryAfter;
        public RateLimitException(Duration retryAfter) { super("Plugin request rate limit exceeded; retry after " + retryAfter); this.retryAfter = retryAfter; }
        public Duration retryAfter() { return retryAfter; }
    }
}
